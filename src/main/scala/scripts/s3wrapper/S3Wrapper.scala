package scripts.s3wrapper

import com.amazonaws.auth.{AWSStaticCredentialsProvider, BasicAWSCredentials}
import com.amazonaws.services.s3.model.{CreateBucketRequest, ListObjectsV2Request, ListObjectsV2Result}
import com.amazonaws.services.s3.transfer.{MultipleFileDownload, TransferManager, TransferManagerBuilder}
import com.amazonaws.services.s3.{AmazonS3, AmazonS3ClientBuilder}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.io.FileUtils
import org.apache.commons.lang3.StringUtils
import org.ini4j.Wini
import scripts.s3wrapper.S3Wrapper.validateNotBlank

import java.nio.file.{Files, Path}
import java.util.concurrent.atomic.AtomicReference
import scala.annotation.tailrec
import scala.collection.JavaConverters._

class S3Wrapper(accessKey: String, secretKey: String) extends AutoCloseable with StrictLogging {
  validateNotBlank("accessKey", accessKey)
  validateNotBlank("secretKey", secretKey)

  private val wholeDirectory = ""

  private val awsCredentials = new BasicAWSCredentials(accessKey, secretKey)

  private val s3Client: AmazonS3 = AmazonS3ClientBuilder
    .standard()
    .withCredentials(new AWSStaticCredentialsProvider(awsCredentials))
    .build()

  private lazy val cachedBuckets = new AtomicReference[Set[S3Bucket]](listBucketNamesInternal)

  /**
    * Retrieve all available buckets and its region. It is equivalent of the following command
    * `aws s3 ls` or `aws s3api list-buckets --query "Buckets[].Name"`
    * combined with the following
    * `aws s3api get-bucket-location --bucket my-bucket` per each bucket
    * It also update internal cache with returned buckets
    *
    * @return
    */
  def buckets: Set[S3Bucket] = {
    cachedBuckets.get()
  }

  def refreshBucketNames: Set[S3Bucket] = {
    cachedBuckets.updateAndGet(_ => listBucketNamesInternal)
  }

  private def listBucketNamesInternal: Set[S3Bucket] = {
    s3Client
      .listBuckets()
      .asScala
      .par
      .map { b =>
        val location = s3Client.getBucketLocation(b.getName)
        S3Bucket(b.getName, location)
      }
      .toSet
      .seq
  }

  /**
    * Get recursively all keys belongs to some bucket. Since AWS API limits to 1000
    * the maximum amount of keys per request, it might be needed to apply multiple requests
    * The CLI equivalent command is as following:
    * `aws s3 ls --summarize --recursive s3://beam-admin-reports/ --profile beam`
    *
    * @param bucketName  the name of the bucket
    * @param maxRequests the maximum number of requests. Default value is unlimited in practical terms (Int.MaxValue)
    * @return a set with all keys from the bucket
    */
  def allBucketFiles(bucketName: String, maxRequests: Int = Int.MaxValue): Set[S3RemoteFile] = {
    val request = new ListObjectsV2Request()
      .withBucketName(bucketName)
      .withMaxKeys(1000)
    allBucketFiles(request, Set.empty, maxRequests)
  }

  @tailrec
  private def allBucketFiles(request: ListObjectsV2Request, acc: Set[S3RemoteFile], max: Int): Set[S3RemoteFile] = {
    if (max > 0) {
      val result: ListObjectsV2Result = s3Client.listObjectsV2(request)
      val resultKeys = result.getObjectSummaries.asScala
        .map(o => S3RemoteFile(o.getKey, o.getSize))
        .toSet
      if (result.isTruncated) {
        val token = result.getNextContinuationToken
        val newRequest = request.withContinuationToken(token)
        allBucketFiles(newRequest, acc ++ resultKeys, max - 1)
      } else {
        acc ++ resultKeys
      }
    } else {
      acc
    }
  }

  /**
    * Download the full bucket and save into the destination path
    * @param bucketName the bucket name
    * @param destinationPath the destination path
    */
  def downloadBucket(bucketName: String, destinationPath: Path): Unit = {
    download(bucketName, wholeDirectory, destinationPath)
  }

  /**
    * Download all files, directories and subdirectories accordingly to the parameters
    * @param bucketName the bucket name
    * @param keysStartingWith the object key is composed its full path
    * @param destinationPath the directory which downloaded data will be saved
    */
  final def download(bucketName: String, keysStartingWith: String, destinationPath: Path): Unit = {
    if (!Files.isDirectory(destinationPath)) {
      throw new IllegalArgumentException(s"Destination path [$destinationPath] must be a directory")
    }
    if (Files.exists(destinationPath.resolve(bucketName))) {
      throw new IllegalArgumentException(
        s"Destination path [$destinationPath] already contains a directory with the name [$bucketName]"
      )
    }

    val transferManager: TransferManager = TransferManagerBuilder.standard
      .withS3Client(s3Client)
      .build

    val downloader: MultipleFileDownload =
      transferManager.downloadDirectory(bucketName, keysStartingWith, destinationPath.toFile)

    logger.info(s"Downloading directory [$keysStartingWith] from bucket [$bucketName] to [$destinationPath] started.")
    downloader.waitForCompletion()
    logger.info(
      s"Downloading directory [$keysStartingWith] from bucket [$bucketName] to [$destinationPath] has finished."
    )
  }

  def uploadFile(bucketName: String, destinationKey: String, fileSourcePath: Path): Unit = {
    if (!Files.isDirectory(fileSourcePath)) {
      throw new IllegalArgumentException(s"Destination path [$fileSourcePath] must be a directory")
    }

    val transferManager: TransferManager = TransferManagerBuilder.standard
      .withS3Client(s3Client)
      .build

    val uploader = transferManager.upload(bucketName, destinationKey, fileSourcePath.toFile)
    uploader.waitForCompletion()
  }

  def deleteRemoteObject(bucketName: String, objectKey: String): Unit = {
    s3Client.deleteObject(bucketName, objectKey)
  }

  /**
    * Create a bucket in a specific region
    * @param bucketName the value of bucketName
    * @param region the region which bucket must be created
    */
  def createBucket(bucketName: String, region: String): Unit = {
    val request = new CreateBucketRequest(bucketName, region)
    s3Client.createBucket(request)
  }

  /**
    * Create the specified bucket accordingly to the param @bucket
    * @param bucket the wrapper object containing name and region
    */
  def createBucket(bucket: S3Bucket): Unit = {
    createBucket(bucket.name, bucket.region)
  }

  /**
    * Delete the @bucketName if and only if the bucket is empty
    * @param bucketName the name of the bucket
    */
  def removeBucket(bucketName: String): Unit = {
    s3Client.deleteBucket(bucketName)
  }

  /**
    * Checks if the specified bucket exists (Amazon S3 buckets are named in a global namespace)
    * Is just a wrapper around doesBucketExistV2 sdk library
    * @param bucketName the name of the bucket
    * @return the value true if the @bucketName exists in S3; the value false if @bucketName does not exists in S3
    */
  def doesBucketExists(bucketName: String): Boolean = {
    s3Client.doesBucketExistV2(bucketName)
  }

  /**
    * Call aws shutdown method.
    * shutdown method states "shuts down this client object, releasing any resources that might be held open."
    */
  override def close(): Unit = {
    s3Client.shutdown()
  }
}

object S3Wrapper extends StrictLogging {

  /**
    *  Search for credentials in the section [default] of  ~/.aws/credentials file
    * @return an instance of S3Wrapper
    */
  def fromCredentialDefault(): S3Wrapper = {
    fromCredential("default")
  }

  def fromCredential(profile: String): S3Wrapper = {
    fromCredential(profile, None)
  }

  def fromCredential(profile: String, region: String): S3Wrapper = {
    fromCredential(profile, Some(region))
  }

  private def fromCredential(profile: String, region: Option[String]): S3Wrapper = {
    val filePath = FileUtils.getUserDirectory.toPath.resolve(".aws").resolve("credentials")
    fromCredentialFile(filePath, profile, region)
  }

  def fromCredentialFile(filePath: Path, profile: String, region: Option[String] = None): S3Wrapper = {
    logger.info(s"Loading profile [$profile] from information file [$filePath].")
    if (!Files.isRegularFile(filePath)) {
      throw new IllegalArgumentException(s"File [$filePath] is not a regular file")
    }
    val ini = new Wini(filePath.toFile)
    val accessKey = ini.get(profile, "aws_access_key_id", classOf[String])
    val secretKey = ini.get(profile, "aws_secret_access_key", classOf[String])

    new S3Wrapper(accessKey, secretKey)
  }

  private def validateNotBlank(field: String, fieldValue: String): Unit = {
    require(!StringUtils.isBlank(fieldValue), s"[$field] cannot be blank (null/empty string/whitespaces)")
  }

}
