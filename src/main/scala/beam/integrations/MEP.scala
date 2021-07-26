package beam.integrations

import beam.sim.config.BeamConfig
import beam.utils.logging.ExponentialLazyLogging
import org.apache.commons.lang3.exception.ExceptionUtils

import java.nio.charset.StandardCharsets
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsSessionCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.sts
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.{InvocationType, InvokeRequest, LogType}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.Failure

case class MEPResponse(configParseResult: String, awsReadResult: String)

class MEP(beamConfig: BeamConfig) extends ExponentialLazyLogging {
  //TODO: Add README section on MEP setup - especially wrt configs
  val mepAwsArn: String = beamConfig.beam.mep.aws.arn
  val mepAwsRegion: String = beamConfig.beam.mep.aws.region
  val mepAwsExternalId: String = beamConfig.beam.mep.aws.externalId
  val mepAwsLambdaFunctionName: String = beamConfig.beam.mep.aws.lambda.functionName
  val beamAwsAccessKeyId: String = beamConfig.beam.aws.accessKeyId
  val beamAwsSecretKey: String = beamConfig.beam.aws.secretKey
  val beamAwsRegion: String = beamConfig.beam.aws.region

  def submit(payload:String, sessionName: String = "BEAM_Submit"): Future[MEPResponse] = {
    val mepResponseFuture = for {
      credentialsForLambda <- getAssumedCredentials(sessionName)
      mepResponse <- invokeLambdaUsing(credentialsForLambda, payload)
    } yield mepResponse
    mepResponseFuture.onComplete{
      case Failure(ex) => logger.error(s"Error submitting the MEP payload. Exception message: ${ex.getMessage}; Stack trace: ${ExceptionUtils.getStackTrace(ex)}")
      case _ =>
    }
    mepResponseFuture
  }

  def convertToMEPResponse(usingResponsePayload: String): MEPResponse = {
    //Example responses:
    // { "config_parse_result": "ok", "aws_read_result": "ok" }
    // { "config_parse_result": ["could not find required key mepVersion"], "aws_read_result": "unknown" }

    //TODO: Use Spray to decompose the response
    ???
  }

  def invokeLambdaUsing(assumedCredentials: AwsSessionCredentials, payload: String): Future[MEPResponse] = Future {
    val lambdaClient = LambdaClient.builder
      .region(Region.of(mepAwsRegion))
      .credentialsProvider(StaticCredentialsProvider.create(assumedCredentials))
      .build

    val lambdaRequest = InvokeRequest.builder
      .functionName(mepAwsLambdaFunctionName)
      .invocationType(InvocationType.REQUEST_RESPONSE)
      .logType(LogType.TAIL)
      .payload(SdkBytes.fromString(payload, StandardCharsets.UTF_8))
      .build

    val lambdaResponse = lambdaClient.invoke(lambdaRequest)
    val responsePayload = lambdaResponse.payload.asUtf8String()
    convertToMEPResponse(responsePayload)
  }

  def getAssumedCredentials(usingSessionName: String): Future[AwsSessionCredentials] = Future {
    val beamAwsCredentials = new AwsBasicCredentials(beamAwsAccessKeyId, beamAwsSecretKey)

    val stsClient = sts.StsClient.builder
      .region(Region.of(beamAwsRegion))
      .credentialsProvider(StaticCredentialsProvider.create(beamAwsCredentials))
      .build
    val assumeRoleRequest = sts.model.AssumeRoleRequest.builder
      .roleArn(mepAwsArn)
      .externalId(mepAwsExternalId)
      .roleSessionName(usingSessionName)
      .build
    val assumeRoleResponse = stsClient.assumeRole(assumeRoleRequest)
    val assumedRoleCredentials = assumeRoleResponse.credentials()
    AwsSessionCredentials.create(assumedRoleCredentials.accessKeyId, assumedRoleCredentials.secretAccessKey, assumedRoleCredentials.sessionToken)
  }
}
