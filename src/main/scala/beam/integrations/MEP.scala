package beam.integrations

import beam.sim.config.BeamConfig

import java.nio.charset.StandardCharsets
import software.amazon.awssdk.auth.credentials.{AwsBasicCredentials, AwsSessionCredentials, StaticCredentialsProvider}
import software.amazon.awssdk.core.SdkBytes
import software.amazon.awssdk.services.sts
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.lambda.LambdaClient
import software.amazon.awssdk.services.lambda.model.{InvocationType, InvokeRequest, LogType}

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class MEPResponse(configParseResult: String, awsReadResult: String)

class MEP(beamConfig: BeamConfig) {
  //TODO: Add configs - should some be fed in other ways if need more security (so not checked into code by accident?)
  val mepAwsArn: String = beamConfig.beam.mep.aws.arn //arn:aws:iam::991404956194:role/mep-cross-lab-access
  val mepAwsRegion: String = beamConfig.beam.mep.aws.region //us-west-2
  val mepAwsExternalId: String = beamConfig.beam.mep.aws.externalId //jafijk887fk88hj0n
  val mepAwsLambdaFunctionName: String = beamConfig.beam.mep.aws.lambda.functionName //mep-dev-mep-validation
  val beamAwsAccessKeyId: String = beamConfig.beam.aws.accessKeyId
  val beamAwsSecretKey: String = beamConfig.beam.aws.secretKey
  val beamAwsRegion: String = beamConfig.beam.aws.region //us-east-2

  def submit(payload:String, sessionName: String = "BEAM_Submit"): Future[MEPResponse] = {
    //TODO: Make sure the errors are not swallowed
    for {
      credentialsForLambda <- getAssumedCredentials(sessionName)
      mepResponse <- invokeLambdaUsing(credentialsForLambda, payload)
    } yield mepResponse
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
