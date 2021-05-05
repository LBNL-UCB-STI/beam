package scripts

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.s3.transfer.{TransferManager, TransferManagerBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.ini4j.Wini
import scripts.S3Adapter.validateNotBlank

import java.nio.file.{Files, Path}
import scala.collection.JavaConverters._


class S3Adapter(accessKey: String, secretKey: String, region: String) extends AutoCloseable {
  validateNotBlank("accessKey", accessKey)
  validateNotBlank("secretKey", secretKey)
  validateNotBlank("region", region)

  private val awsCredentials = new BasicAWSCredentials(accessKey, secretKey)

  private val s3Client: AmazonS3 = AmazonS3ClientBuilder
    .standard()
    .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
    .withRegion(region)
    .build()

  def listBucketNames: Set[String] = {
    s3Client
      .listBuckets()
      .asScala
      .map { b =>
        b.getName
      }
      .toSet
  }

  private val wholeDirectory = ""

  def downloadBucket(bucketName: String, destinationPath: Path): Unit = {
    downloadBucketDirectory(bucketName, wholeDirectory, destinationPath)
  }

  def downloadBucketDirectory(bucketName: String, bucketDirectory: String, destinationPath: Path): Unit = {
    if (!Files.isDirectory(destinationPath)) {
      throw new IllegalArgumentException(s"Destination path [$destinationPath] must be a directory")
    }
    if (Files.exists(destinationPath.resolve(bucketName))) {
      throw new IllegalArgumentException(s"Destination path [$destinationPath] cannot contain a directory with the bucket name [$bucketName]")
    }
    downloadBucketDirectoryInternal(bucketName, bucketDirectory, destinationPath)
  }

  private def downloadBucketDirectoryInternal(
    bucketName: String,
    bucketDirectory: String,
    destinationPath: Path
  ): Unit = {

    val transferManager: TransferManager = TransferManagerBuilder.standard
      .withS3Client(s3Client)
      .build

    val downloader = transferManager.downloadDirectory(bucketName, bucketDirectory, destinationPath.toFile)
    downloader.waitForCompletion()
  }

  override def close(): Unit = {
    s3Client.shutdown()
  }
}

object S3Adapter extends StrictLogging {

  def fromCredentialDefault(): S3Adapter = {
    fromCredential("default")
  }

  def fromCredential(profile: String): S3Adapter = {
    val filePath = FileUtils.getUserDirectory.toPath.resolve(".aws").resolve("credentials")
    fromCredentialFile(filePath, profile)
  }

  def fromCredentialFile(filePath: Path, profile: String): S3Adapter = {
    logger.info(s"Loading profile [$profile] from information file [$filePath].")
    if (!Files.isRegularFile(filePath)) {
      throw new IllegalArgumentException(s"File [$filePath] is not a regular file")
    }
    val ini = new Wini(filePath.toFile)
    val accessKey = ini.get(profile, "aws_access_key_id", classOf[String])
    val secretKey = ini.get(profile, "aws_secret_access_key", classOf[String])
    val region = ini.get(profile, "region", classOf[String])

    if (hasSomeNullOrEmpty(accessKey, secretKey, region)) {
      val msg = s"The file [$filePath] must define values for 'aws_access_key_id', " +
        s"'aws_secret_access_key' and 'region' for profile [$profile]"
      throw new IllegalStateException(msg)
    }

    new S3Adapter(accessKey, secretKey, region)
  }

  private def hasSomeNullOrEmpty(args: String*): Boolean = {
    args.contains(null) || args.exists(_.trim.isEmpty)
  }

  private def validateNotBlank(field: String, fieldValue: String): Unit = {
    require(!StringUtils.isBlank(fieldValue), s"[$field] cannot be blank (null/empty string/whitespaces)")
  }

}

sealed trait S3RemoteResource {
  def url: String
}
case class S3RemoteFile(url: String, sizeInBytes: Long)
case class S3RemoteDirectory(content: Set[S3RemoteResource])
