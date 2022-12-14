package com.netflix.spinnaker.clouddriver.hecloud.deploy.handlers

import com.netflix.spinnaker.clouddriver.data.task.Task
import com.netflix.spinnaker.clouddriver.data.task.TaskRepository
import com.netflix.spinnaker.clouddriver.deploy.DeployDescription
import com.netflix.spinnaker.clouddriver.deploy.DeployHandler
import com.netflix.spinnaker.clouddriver.deploy.DeploymentResult
import com.netflix.spinnaker.clouddriver.hecloud.deploy.HeCloudServerGroupNameResolver
import com.netflix.spinnaker.clouddriver.hecloud.deploy.description.HeCloudDeployDescription
import com.netflix.spinnaker.clouddriver.hecloud.exception.HeCloudOperationException
import com.netflix.spinnaker.clouddriver.hecloud.provider.view.HeCloudClusterProvider
import com.netflix.spinnaker.clouddriver.hecloud.client.HeCloudAutoScalingClient
import com.netflix.spinnaker.clouddriver.hecloud.client.HeCloudEyeClient
import groovy.util.logging.Slf4j
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.stereotype.Component

/*
curl -X POST \
  http://localhost:7002/heweicloud/ops \
  -H 'Content-Type: application/json' \
  -H 'Postman-Token: 16583564-d31a-442f-bb17-0a308ee2c529' \
  -H 'cache-control: no-cache' \
  -d '[{"createServerGroup":{"application":"myapp","stack":"dev","accountName":"test","imageId":"img-oikl1tzv","instanceType":"S2.SMALL2","zones":["ap-guangzhou-2"],"credentials":"my-account-name","maxSize":0,"minSize":0,"desiredCapacity":0,"vpcId":"","region":"ap-guangzhou","dataDisks":[{"diskType":"CLOUD_PREMIUM","diskSize":50}],"systemDisk":{"diskType":"CLOUD_PREMIUM","diskSize":50}}}]'
*/


@Component
@Slf4j
class HeCloudDeployHandler implements DeployHandler<HeCloudDeployDescription> {
  private static final String BASE_PHASE = "DEPLOY"

  @Autowired
  private HeCloudClusterProvider heCloudClusterProvider

  private static Task getTask() {
    TaskRepository.threadLocalTask.get()
  }

  @Override
  boolean handles(DeployDescription description) {
    description instanceof HeCloudDeployDescription
  }

  @Override
  DeploymentResult handle(HeCloudDeployDescription description, List priorOutputs) {
    task.updateStatus BASE_PHASE, "Initializing deployment to ${description.zones}"

    def accountName = description.accountName
    def region = description.region
    def serverGroupNameResolver = new HeCloudServerGroupNameResolver(
      accountName, region, heCloudClusterProvider, description.credentials)

    task.updateStatus BASE_PHASE, "Looking up next sequence..."

    def serverGroupName = serverGroupNameResolver.resolveNextServerGroupName(description.application, description.stack, description.detail, false)

    task.updateStatus BASE_PHASE, "Produced server group name: $serverGroupName"

    description.serverGroupName = serverGroupName

    HeCloudAutoScalingClient autoScalingClient = new HeCloudAutoScalingClient(
      description.credentials.credentials.accessKeyId,
      description.credentials.credentials.accessSecretKey,
      region
    )

    if (description?.source?.useSourceCapacity) {
      log.info('copy source server group capacity')
      String sourceServerGroupName = description?.source?.serverGroupName
      String sourceRegion = description?.source?.region
      def sourceServerGroup = heCloudClusterProvider.getServerGroup(accountName, sourceRegion, sourceServerGroupName)
      if (!sourceServerGroup) {
        log.warn("source server group $sourceServerGroupName is not found")
      } else {
        // use source current num as desired capacity
        def sourceAsg = autoScalingClient.getAutoScalingGroupsByName(sourceServerGroupName)[0]
        description.desiredCapacity = sourceAsg.getCurrentInstanceNumber() as Integer
        description.maxSize = sourceServerGroup.asg.maxSize as Integer
        description.minSize = sourceServerGroup.asg.minSize as Integer
        // use source halth periodic parameters
        description.healthAuditMethod = sourceServerGroup.asg.healthAuditMethod as String
        description.healthPeriodicTime = sourceServerGroup.asg.healthPeriodicTime as Integer
        description.healthGracePeriod = sourceServerGroup.asg.healthGracePeriod as Integer
      }
    }

    task.updateStatus BASE_PHASE, "Composing server group $serverGroupName..."

    autoScalingClient.deploy(description)

    task.updateStatus BASE_PHASE, "Done creating server group $serverGroupName in $region."

    DeploymentResult deploymentResult = new DeploymentResult()
    deploymentResult.serverGroupNames = ["$region:$serverGroupName".toString()]
    deploymentResult.serverGroupNameByRegion[region] = serverGroupName

    if (description.copySourceScalingPoliciesAndActions) {
      copyScalingPolicy(description, deploymentResult)
      copyNotification(description, deploymentResult)  // copy notification by the way
      copyLifeCycleHook(description, deploymentResult)
    }
    return deploymentResult
  }

  private def copyNotification(HeCloudDeployDescription description, DeploymentResult deployResult) {
    task.updateStatus BASE_PHASE, "Enter copyNotification."
    String sourceServerGroupName = description?.source?.serverGroupName
    String sourceRegion = description?.source?.region
    String accountName = description?.accountName
    def sourceServerGroup = heCloudClusterProvider.getServerGroup(accountName, sourceRegion, sourceServerGroupName)

    if (!sourceServerGroup) {
      log.warn("source server group not found, account $accountName, region $sourceRegion, source sg name $sourceServerGroupName")
      return
    }

    String sourceAsgId = sourceServerGroup.asg.autoScalingGroupId

    task.updateStatus BASE_PHASE, "Initializing copy notification from $sourceAsgId."

    HeCloudAutoScalingClient autoScalingClient = new HeCloudAutoScalingClient(
      description.credentials.credentials.accessKeyId,
      description.credentials.credentials.accessSecretKey,
      sourceRegion
    )

    String newServerGroupName = deployResult.serverGroupNameByRegion[sourceRegion]
    def newAsg = autoScalingClient.getAutoScalingGroupsByName(newServerGroupName)[0]
    String newAsgId = newAsg.getScalingGroupId()

    def notifications = autoScalingClient.getNotification(sourceAsgId)
    for (notification in notifications) {
      try {
        autoScalingClient.createNotification(newAsgId, notification)
      } catch (HeCloudOperationException e) {
        // something bad happened during creation, log the error and continue
        log.warn "create notification error $e"
        autoScalingClient.deleteAutoScalingGroup(newAsgId)
        log.warn "delete scaling group ${newAsgId}"
        throw e
      }
    }
  }

  private def copyLifeCycleHook(HeCloudDeployDescription description, DeploymentResult deployResult) {
    task.updateStatus BASE_PHASE, "Enter copyLifeCycleHook."
    String sourceServerGroupName = description?.source?.serverGroupName
    String sourceRegion = description?.source?.region
    String accountName = description?.accountName
    def sourceServerGroup = heCloudClusterProvider.getServerGroup(accountName, sourceRegion, sourceServerGroupName)

    if (!sourceServerGroup) {
      log.warn("source server group not found, account $accountName, region $sourceRegion, source sg name $sourceServerGroupName")
      return
    }

    String sourceAsgId = sourceServerGroup.asg.autoScalingGroupId

    task.updateStatus BASE_PHASE, "Initializing copy lifecyclehook from $sourceAsgId."

    HeCloudAutoScalingClient autoScalingClient = new HeCloudAutoScalingClient(
      description.credentials.credentials.accessKeyId,
      description.credentials.credentials.accessSecretKey,
      sourceRegion
    )

    String newServerGroupName = deployResult.serverGroupNameByRegion[sourceRegion]
    def newAsg = autoScalingClient.getAutoScalingGroupsByName(newServerGroupName)[0]
    String newAsgId = newAsg.getScalingGroupId()

    def hooks = autoScalingClient.getLifeCycleHook(sourceAsgId)
    for (hook in hooks) {
      try {
        autoScalingClient.createLifeCycleHook(newAsgId, hook)
      } catch (HeCloudOperationException e) {
        // something bad happened during creation, log the error and continue
        log.warn "create hook error $e"
        autoScalingClient.deleteAutoScalingGroup(newAsgId)
        log.warn "delete scaling group ${newAsgId}"
        throw e
      }
    }
  }

  private def copyScalingPolicy(HeCloudDeployDescription description, DeploymentResult deployResult) {
    task.updateStatus BASE_PHASE, "Enter copyScalingPolicy."

    String sourceServerGroupName = description?.source?.serverGroupName
    String sourceRegion = description?.source?.region
    String accountName = description?.accountName
    def sourceServerGroup = heCloudClusterProvider.getServerGroup(accountName, sourceRegion, sourceServerGroupName)

    if (!sourceServerGroup) {
      log.warn("description is $description")
      log.warn("source server group not found, account $accountName, region $sourceRegion, source sg name $sourceServerGroupName")
      return
    }

    String sourceAsgId = sourceServerGroup.asg.autoScalingGroupId

    task.updateStatus BASE_PHASE, "Initializing copy scaling policy from $sourceAsgId."

    HeCloudAutoScalingClient autoScalingClient = new HeCloudAutoScalingClient(
      description.credentials.credentials.accessKeyId,
      description.credentials.credentials.accessSecretKey,
      sourceRegion
    )

    HeCloudEyeClient cloudEyeClient = new HeCloudEyeClient(
      description.credentials.credentials.accessKeyId,
      description.credentials.credentials.accessSecretKey,
      sourceRegion
    )

    String newServerGroupName = deployResult.serverGroupNameByRegion[sourceRegion]
    def newAsg = autoScalingClient.getAutoScalingGroupsByName(newServerGroupName)[0]
    String newAsgId = newAsg.getScalingGroupId()

    // copy all scaling policies
    def scalingPolicies = autoScalingClient.getScalingPolicies(sourceAsgId)
    for (scalingPolicy in scalingPolicies) {
      try {
        def alarmId = scalingPolicy.getAlarmId()
        if (alarmId) {
          def alarm = cloudEyeClient.getAlarm(alarmId)
          def newAlarm = cloudEyeClient.createAlarm(alarm, newAsgId)
          alarmId = newAlarm.getAlarmId()
        }
        autoScalingClient.createScalingPolicy(newAsgId, newServerGroupName, scalingPolicy, alarmId)
      } catch (HeCloudOperationException e) {
        // something bad happened during creation, log the error and continue
        log.warn "create scaling policy error $e"
        autoScalingClient.deleteAutoScalingGroup(newAsgId)
        log.warn "delete scaling group ${newAsgId}"
        throw e
      }
    }
  }
}
