package com.gilt.aws.lambda

import com.amazonaws.services.lambda.model.{Environment, FunctionCode, UpdateFunctionCodeRequest, VpcConfig}

import collection.JavaConversions._
import sbt._

import scala.util.{Failure, Success}

object AwsLambdaPlugin extends AutoPlugin {

  object autoImport {
    val createLambda = taskKey[Map[String, LambdaARN]]("Create a new AWS Lambda function from the current project")
    val updateLambda = taskKey[Map[String, LambdaARN]]("Package and deploy the current project to an existing AWS Lambda")
    val configureLambda = taskKey[Map[String, LambdaARN]]("Update the function configuration of an existing AWS Lambda")

    val s3Bucket = settingKey[Option[String]]("ID of the S3 bucket where the jar will be uploaded")
    val s3KeyPrefix = settingKey[String]("The prefix to the S3 key where the jar will be uploaded")
    val lambdaName = settingKey[Option[String]]("Name of the AWS Lambda to update")
    val handlerName = settingKey[Option[String]]("Name of the handler to be executed by AWS Lambda")
    val roleArn = settingKey[Option[String]]("ARN of the IAM role for the Lambda function")
    val region = settingKey[Option[String]]("Name of the AWS region to connect to")
    val awsLambdaTimeout = settingKey[Option[Int]]("The Lambda timeout length in seconds (1-300)")
    val awsLambdaMemory = settingKey[Option[Int]]("The amount of memory in MB for the Lambda function (128-1536, multiple of 64)")
    val lambdaHandlers = settingKey[Seq[(String, String)]]("A sequence of pairs of Lambda function names to handlers (for multiple handlers in one jar)")
    val deployMethod = settingKey[Option[String]]("S3 for using an S3 bucket to upload the jar or DIRECT for directly uploading a jar file.")
    val deadLetterArn = settingKey[Option[String]]("ARN of the Dead Letter Queue or Topic to send unprocessed messages")
    val vpcConfigSubnetIds = settingKey[Option[String]]("Comma separated list of subnet IDs for the VPC")
    val vpcConfigSecurityGroupIds = settingKey[Option[String]]("Comma separated list of security group IDs for the VPC")
    val environment = settingKey[Seq[(String, String)]]("A sequence of environment keys and values")
  }

  import autoImport._

  override def requires = sbtassembly.AssemblyPlugin

  override lazy val projectSettings = Seq(
    updateLambda := doUpdateLambda(
      deployMethod = deployMethod.value,
      region = region.value,
      jar = sbtassembly.AssemblyKeys.assembly.value,
      s3Bucket = s3Bucket.value,
      s3KeyPrefix = s3KeyPrefix.?.value,
      lambdaName = lambdaName.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value
    ),
    configureLambda := doConfigureLambda(
      lambdaName = lambdaName.value,
      region = region.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value,
      roleArn = roleArn.value,
      timeout = awsLambdaTimeout.value,
      memory = awsLambdaMemory.value,
      deadLetterArn = deadLetterArn.value,
      vpcConfigSubnetIds = vpcConfigSubnetIds.value,
      vpcConfigSecurityGroupIds = vpcConfigSecurityGroupIds.value,
      environment = environment.value
    ),
    createLambda := doCreateLambda(
      deployMethod = deployMethod.value,
      region = region.value,
      jar = sbtassembly.AssemblyKeys.assembly.value,
      s3Bucket = s3Bucket.value,
      s3KeyPrefix = s3KeyPrefix.?.value,
      lambdaName = lambdaName.value,
      handlerName = handlerName.value,
      lambdaHandlers = lambdaHandlers.value,
      roleArn = roleArn.value,
      timeout = awsLambdaTimeout.value,
      memory = awsLambdaMemory.value,
      deadLetterArn = deadLetterArn.value,
      vpcConfigSubnetIds = vpcConfigSubnetIds.value,
      vpcConfigSecurityGroupIds = vpcConfigSecurityGroupIds.value,
      environment = environment.value
    ),
    s3Bucket := None,
    lambdaName := Some(sbt.Keys.name.value),
    handlerName := None,
    lambdaHandlers := List.empty[(String, String)],
    roleArn := None,
    region := None,
    deployMethod := Some("S3"),
    awsLambdaMemory := None,
    awsLambdaTimeout := None,
    deadLetterArn := None,
    vpcConfigSubnetIds := None,
    vpcConfigSecurityGroupIds := None,
    environment := Nil
  )

  private def doUpdateLambda(deployMethod: Option[String], region: Option[String], jar: File, s3Bucket: Option[String], s3KeyPrefix: Option[String],
                             lambdaName: Option[String], handlerName: Option[String], lambdaHandlers: Seq[(String, String)]): Map[String, LambdaARN] = {
    val resolvedDeployMethod = resolveDeployMethod(deployMethod)
    val resolvedRegion = resolveRegion(region)
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)

    if (resolvedDeployMethod.value == "S3") {
      val resolvedBucketId = resolveBucketId(s3Bucket)
      val resolvedS3KeyPrefix = resolveS3KeyPrefix(s3KeyPrefix)

      AwsS3.pushJarToS3(jar, resolvedBucketId, resolvedS3KeyPrefix) match {
        case Success(s3Key) => (for (resolvedLambdaName <- resolvedLambdaHandlers.keys) yield {
          val updateFunctionCodeRequest = AwsLambda.createUpdateFunctionCodeRequestFromS3(resolvedBucketId, s3Key, resolvedLambdaName)

          updateFunctionCode(resolvedRegion, resolvedLambdaName, updateFunctionCodeRequest)
        }).toMap
        case Failure(exception) =>
          sys.error(s"Error uploading jar to S3 lambda: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
      }
    } else if (resolvedDeployMethod.value == "DIRECT") {
      (for (resolvedLambdaName <- resolvedLambdaHandlers.keys) yield {
        val updateFunctionCodeRequest = AwsLambda.createUpdateFunctionCodeRequestFromJar(jar, resolvedLambdaName)

        updateFunctionCode(resolvedRegion, resolvedLambdaName, updateFunctionCodeRequest)
      }).toMap
    } else
      sys.error(s"Unsupported deploy method: ${resolvedDeployMethod.value}")
  }

  def updateFunctionCode(resolvedRegion: Region, resolvedLambdaName: LambdaName, updateFunctionCodeRequest: UpdateFunctionCodeRequest): (String, LambdaARN) = {
    AwsLambda.updateLambdaWithFunctionCodeRequest(resolvedRegion, updateFunctionCodeRequest) match {
      case Success(updateFunctionCodeResult) =>
        resolvedLambdaName.value -> LambdaARN(updateFunctionCodeResult.getFunctionArn)
      case Failure(exception) =>
        sys.error(s"Error updating lambda: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
    }
  }

  private def doConfigureLambda(lambdaName: Option[String], region: Option[String],
                                handlerName: Option[String], lambdaHandlers: Seq[(String, String)], roleArn: Option[String], timeout: Option[Int], memory: Option[Int], deadLetterArn: Option[String], vpcConfigSubnetIds: Option[String], vpcConfigSecurityGroupIds: Option[String], environment: Seq[(String, String)]): Map[String, LambdaARN] = {
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)
    val resolvedRegion = resolveRegion(region)
    val resolvedRoleName = resolveRoleARN(roleArn)
    val resolvedTimeout = resolveTimeout(timeout)
    val resolvedMemory = resolveMemory(memory)
    val resolvedDeadLetterArn = resolveDeadLetterARN(deadLetterArn)
    val resolvedVpcConfigSubnetIds = resolveVpcConfigSubnetIds(vpcConfigSubnetIds)
    val resolvedVpcConfigSecurityGroupIds = resolveVpcConfigSecurityGroupIds(vpcConfigSecurityGroupIds)

    val resolvedVpcConfig = {
      if (resolvedVpcConfigSubnetIds.isDefined || resolvedVpcConfigSecurityGroupIds.isDefined){
        val config = new VpcConfig()
        if (resolvedVpcConfigSubnetIds.isDefined) config.setSubnetIds(resolvedVpcConfigSubnetIds.get.value.split(",").toSeq)
        if (resolvedVpcConfigSecurityGroupIds.isDefined) config.setSecurityGroupIds(resolvedVpcConfigSecurityGroupIds.get.value.split(",").toSeq)
        Some(config)
      } else {
        None
      }
    }
    val resolvedEnvironment = resolveEnvironment(environment)
    for ((resolvedLambdaName, resolvedHandlerName) <- resolvedLambdaHandlers) yield {
      AwsLambda.getLambdaConfig(resolvedRegion, resolvedLambdaName).flatMap { configOpt =>
        configOpt.fold {
          println(s"Creating new lambda: ${resolvedLambdaName.value}")
          AwsLambda.createLambda(resolvedRegion, resolvedLambdaName, resolvedHandlerName, resolvedRoleName, resolvedTimeout, resolvedMemory, resolvedDeadLetterArn, resolvedVpcConfig, None, resolvedEnvironment).map(_.getFunctionArn)
        }{ currentConfig =>
          if (currentConfig.getHandler != resolvedHandlerName.value ||
              currentConfig.getRole != resolvedRoleName.value ||
              currentConfig.getRuntime != com.amazonaws.services.lambda.model.Runtime.Java8.toString ||
              (currentConfig.getEnvironment == null && resolvedEnvironment.getVariables.size > 0) ||
              (currentConfig.getEnvironment != null && currentConfig.getEnvironment.getVariables != resolvedEnvironment.getVariables) ||
              resolvedTimeout.exists(t => Integer.valueOf(t.value) != currentConfig.getTimeout) ||
              resolvedMemory.exists(m => Integer.valueOf(m.value) != currentConfig.getMemorySize) ||
              resolvedVpcConfig.exists(vpn => currentConfig.getVpcConfig == null || vpn.getSecurityGroupIds != currentConfig.getVpcConfig.getSecurityGroupIds || vpn.getSubnetIds != currentConfig.getVpcConfig.getSubnetIds)
          ) {
            println(s"Updating existing lambda: ${resolvedLambdaName.value}")
            AwsLambda.updateLambdaConfig(resolvedRegion, resolvedLambdaName, resolvedHandlerName, resolvedRoleName, resolvedTimeout, resolvedMemory,
              resolvedDeadLetterArn, resolvedVpcConfig, resolvedEnvironment).map(_.getFunctionArn)
          } else {
            println(s"Skipping unchanged lambda: ${resolvedLambdaName.value}")
            Success(currentConfig.getFunctionArn)
          }
        }
      } match {
        case Success(functionArn) =>
          resolvedLambdaName.value -> LambdaARN(functionArn)
        case Failure(exception) =>
          sys.error(s"Failed to create or update lambda function: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
      }
    }
  }

  private def doCreateLambda(deployMethod: Option[String], region: Option[String], jar: File, s3Bucket: Option[String], s3KeyPrefix: Option[String], lambdaName: Option[String],
      handlerName: Option[String], lambdaHandlers: Seq[(String, String)], roleArn: Option[String], timeout: Option[Int], memory: Option[Int], deadLetterArn: Option[String], vpcConfigSubnetIds: Option[String], vpcConfigSecurityGroupIds: Option[String], environment: Seq[(String, String)]): Map[String, LambdaARN] = {
    val resolvedDeployMethod = resolveDeployMethod(deployMethod)
    val resolvedRegion = resolveRegion(region)
    val resolvedLambdaHandlers = resolveLambdaHandlers(lambdaName, handlerName, lambdaHandlers)
    val resolvedRoleName = resolveRoleARN(roleArn)
    val resolvedTimeout = resolveTimeout(timeout)
    val resolvedMemory = resolveMemory(memory)
    val resolvedDeadLetterArn = resolveDeadLetterARN(deadLetterArn)
    val resolvedVpcConfigSubnetIds = resolveVpcConfigSubnetIds(vpcConfigSubnetIds)
    val resolvedVpcConfigSecurityGroupIds = resolveVpcConfigSecurityGroupIds(vpcConfigSecurityGroupIds)

    val resolvedVpcConfig = {
      if (resolvedVpcConfigSubnetIds.isDefined || resolvedVpcConfigSecurityGroupIds.isDefined){
        val config = new VpcConfig()
        if (resolvedVpcConfigSubnetIds.isDefined) config.setSubnetIds(resolvedVpcConfigSubnetIds.get.value.split(",").toSeq)
        if (resolvedVpcConfigSecurityGroupIds.isDefined) config.setSecurityGroupIds(resolvedVpcConfigSecurityGroupIds.get.value.split(",").toSeq)
        Some(config)
      } else {
        None
      }
    }
    val resolvedEnvironment = resolveEnvironment(environment)

    if (resolvedDeployMethod.value == "S3") {
      val resolvedBucketId = resolveBucketId(s3Bucket)
      val resolvedS3KeyPrefix = resolveS3KeyPrefix(s3KeyPrefix)
      AwsS3.pushJarToS3(jar, resolvedBucketId, resolvedS3KeyPrefix) match {
        case Success(s3Key) =>
          for ((resolvedLambdaName, resolvedHandlerName) <- resolvedLambdaHandlers) yield {
            val functionCode = AwsLambda.createFunctionCodeFromS3(jar, resolvedBucketId)

            createLambdaWithFunctionCode(resolvedRegion, resolvedRoleName, resolvedTimeout, resolvedMemory,
              resolvedLambdaName, resolvedHandlerName, resolvedDeadLetterArn, resolvedVpcConfig, functionCode, resolvedEnvironment)
          }
        case Failure(exception) =>
          sys.error(s"Error upload jar to S3 lambda: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
      }
    } else if (resolvedDeployMethod.value == "DIRECT") {
      (for ((resolvedLambdaName, resolvedHandlerName) <- resolvedLambdaHandlers) yield {
        val functionCode = AwsLambda.createFunctionCodeFromJar(jar)

        createLambdaWithFunctionCode(resolvedRegion, resolvedRoleName, resolvedTimeout, resolvedMemory,
          resolvedLambdaName, resolvedHandlerName, resolvedDeadLetterArn, resolvedVpcConfig, functionCode, resolvedEnvironment)
      })
    } else
      sys.error(s"Unsupported deploy method: ${resolvedDeployMethod.value}")
  }

  def createLambdaWithFunctionCode(resolvedRegion: Region, resolvedRoleName: RoleARN, resolvedTimeout: Option[Timeout], resolvedMemory: Option[Memory], resolvedLambdaName: LambdaName, resolvedHandlerName: HandlerName, resolvedDeadLetterArn: Option[DeadLetterARN], vpcConfig: Option[VpcConfig], functionCode: FunctionCode, environment: Environment): (String, LambdaARN) = {
    AwsLambda.createLambda(resolvedRegion, resolvedLambdaName, resolvedHandlerName, resolvedRoleName,
      resolvedTimeout, resolvedMemory, resolvedDeadLetterArn, vpcConfig, Some(functionCode), environment) match {
      case Success(createFunctionCodeResult) =>
        resolvedLambdaName.value -> LambdaARN(createFunctionCodeResult.getFunctionArn)
      case Failure(exception) =>
        sys.error(s"Failed to create lambda function: ${exception.getLocalizedMessage}\n${exception.getStackTraceString}")
    }
  }

  private def resolveRegion(sbtSettingValueOpt: Option[String]): Region =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.region) map Region getOrElse promptUserForRegion()

  private def resolveDeployMethod(sbtSettingValueOpt: Option[String]): DeployMethod =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.deployMethod) map DeployMethod getOrElse promptUserForDeployMethod()

  private def resolveBucketId(sbtSettingValueOpt: Option[String]): S3BucketId =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.bucketId) map S3BucketId getOrElse promptUserForS3BucketId()

  private def resolveS3KeyPrefix(sbtSettingValueOpt: Option[String]): String =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.s3KeyPrefix) getOrElse ""

  private def resolveLambdaHandlers(lambdaName: Option[String], handlerName: Option[String],
      lambdaHandlers: Seq[(String, String)]): Map[LambdaName, HandlerName] = {
    val lhs = if (lambdaHandlers.nonEmpty) lambdaHandlers.iterator else {
      val l = lambdaName.getOrElse(sys.env.getOrElse(EnvironmentVariables.lambdaName, promptUserForFunctionName()))
      val h = handlerName.getOrElse(sys.env.getOrElse(EnvironmentVariables.handlerName, promptUserForHandlerName()))
      Iterator(l -> h)
    }
    lhs.map { case (l, h) => LambdaName(l) -> HandlerName(h) }.toMap
  }

  private def resolveEnvironment(kvs: Seq[(String, String)]): Environment = {
    val env = new Environment()
    kvs.foreach { case (k, v) => env.addVariablesEntry(k, v) }
    env
  }

  private def resolveRoleARN(sbtSettingValueOpt: Option[String]): RoleARN =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.roleArn) map RoleARN getOrElse promptUserForRoleARN()

  private def resolveTimeout(sbtSettingValueOpt: Option[Int]): Option[Timeout] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.timeout).map(_.toInt) map Timeout

  private def resolveMemory(sbtSettingValueOpt: Option[Int]): Option[Memory] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.memory).map(_.toInt) map Memory

  private def resolveDeadLetterARN(sbtSettingValueOpt: Option[String]): Option[DeadLetterARN] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.deadLetterArn).map(_.toString) map DeadLetterARN

  private def resolveVpcConfigSubnetIds(sbtSettingValueOpt: Option[String]): Option[VpcConfigSubnetIds] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.vpcConfigSubnetIds).map(_.toString) map VpcConfigSubnetIds

  private def resolveVpcConfigSecurityGroupIds(sbtSettingValueOpt: Option[String]): Option[VpcConfigSecurityGroupIds] =
    sbtSettingValueOpt orElse sys.env.get(EnvironmentVariables.vpcConfigSecurityGroupIds).map(_.toString) map VpcConfigSecurityGroupIds

  private def promptUserForRegion(): Region = {
    val inputValue = readInput(s"Enter the name of the AWS region to connect to. (You also could have set the environment variable: ${EnvironmentVariables.region} or the sbt setting: region)")

    Region(inputValue)
  }

  private def promptUserForDeployMethod(): DeployMethod = {
    val inputValue = readInput(s"Enter the method of deploy you want to use (S3 or DIRECT). (You also could have set the environment variable: ${EnvironmentVariables.deployMethod} or the sbt setting: deployMethod)")

    DeployMethod(inputValue)
  }

  private def promptUserForS3BucketId(): S3BucketId = {
    val inputValue = readInput(s"Enter the AWS S3 bucket where the lambda jar will be stored. (You also could have set the environment variable: ${EnvironmentVariables.bucketId} or the sbt setting: s3Bucket)")
    val bucketId = S3BucketId(inputValue)

    AwsS3.getBucket(bucketId) map (_ => bucketId) getOrElse {
      val createBucket = readInput(s"Bucket $inputValue does not exist. Create it now? (y/n)")

      if(createBucket == "y") {
        AwsS3.createBucket(bucketId) match {
          case Success(createdBucketId) =>
            createdBucketId
          case Failure(th) =>
            println(s"Failed to create S3 bucket: ${th.getLocalizedMessage}")
            promptUserForS3BucketId()
        }
      }
      else promptUserForS3BucketId()
    }
  }

  private def promptUserForFunctionName(): String =
    readInput(s"Enter the name of the AWS Lambda. (You also could have set the environment variable: ${EnvironmentVariables.lambdaName} or the sbt setting: lambdaName)")

  private def promptUserForHandlerName(): String =
    readInput(s"Enter the name of the AWS Lambda handler. (You also could have set the environment variable: ${EnvironmentVariables.handlerName} or the sbt setting: handlerName)")

  private def promptUserForRoleARN(): RoleARN = {
    AwsIAM.basicLambdaRole() match {
      case Some(basicRole) =>
        val reuseBasicRole = readInput(s"IAM role '${AwsIAM.BasicLambdaRoleName}' already exists. Reuse this role? (y/n)")

        if(reuseBasicRole == "y") RoleARN(basicRole.getArn)
        else readRoleARN()
      case None =>
        val createDefaultRole = readInput(s"Default IAM role for AWS Lambda has not been created yet. Create this role now? (y/n)")

        if(createDefaultRole == "y") {
          AwsIAM.createBasicLambdaRole() match {
            case Success(createdRole) =>
              createdRole
            case Failure(th) =>
              println(s"Failed to create role: ${th.getLocalizedMessage}")
              promptUserForRoleARN()
          }
        } else readRoleARN()
    }
  }

  private def readRoleARN(): RoleARN = {
    val inputValue = readInput(s"Enter the ARN of the IAM role for the Lambda. (You also could have set the environment variable: ${EnvironmentVariables.roleArn} or the sbt setting: roleArn)")
    RoleARN(inputValue)
  }

  private def readInput(prompt: String): String =
    SimpleReader.readLine(s"$prompt\n") getOrElse {
      val badInputMessage = "Unable to read input"

      val updatedPrompt = if(prompt.startsWith(badInputMessage)) prompt else s"$badInputMessage\n$prompt"

      readInput(updatedPrompt)
    }
}
