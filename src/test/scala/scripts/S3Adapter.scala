package scripts

import java.nio.file.{Files, Path}

import scala.collection.JavaConverters._

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.s3.AmazonS3ClientBuilder
import com.amazonaws.services.s3.transfer.{TransferManager, TransferManagerBuilder}


class S3Adapter(accessKey: String, secretKey: String, region: String) {

  private val awsCredentials = new BasicAWSCredentials(accessKey, secretKey)

  private val s3Client = AmazonS3ClientBuilder
    .standard()
    .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
    .withRegion(region)
    .build();

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

  def downloadFolder(folderName: String, destinationPath: Path): Seq[Path] = {
    Seq.empty
  }

}

object S3Adapter {

  def fromCredentialFile(profile: String, filePath: Path): S3Adapter = {
    null
  }

  def fromCredential(profile: String): S3Adapter = {
    val prop = new Properties()
    prop.load(new FileReader("~/.config"))
  }

  def fromCredentialDefault(): S3Adapter = {
    fromCredential("default")
  }
}
case class CopyResult(set: Set[Path]) {
  lazy val totalSizeInBytes: Long = {
    set.map(Files.size)
  }.sum
}
