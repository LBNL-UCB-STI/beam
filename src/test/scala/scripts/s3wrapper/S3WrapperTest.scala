package scripts.s3wrapper

import beam.tags.Integration
import beam.utils.FileUtils
import org.apache.commons.io.{FileUtils => ApacheFileUtils}
import org.scalatest.{Assertion, Ignore}
import org.scalatest.funsuite.AnyFunSuite

import java.nio.file.{Files, Path, Paths}
import scala.util.Random

@Ignore
class S3WrapperTest extends AnyFunSuite {
  private val credentialProfile: String = "beam"

  test("create and delete bucket") {
    val regionToCreateBucket = "us-west-2"
    FileUtils.using(S3Wrapper.fromCredential(credentialProfile, regionToCreateBucket)) { s3 =>
      val bucketToBeCreatedName =
        s"beam-s3wrapper-integration-test-${Random.alphanumeric.take(10).mkString.toLowerCase}"
      val bucketToBeCreatedExists = () => s3.doesBucketExists(bucketToBeCreatedName)
      assume(!bucketToBeCreatedExists())
      try {
        s3.createBucket(bucketToBeCreatedName, regionToCreateBucket)
        assert(bucketToBeCreatedExists())
      } finally {
        s3.removeBucket(bucketToBeCreatedName)
      }
      assert(!bucketToBeCreatedExists())
    }
  }

  test("check bucket exist", Integration) {
    FileUtils.using(S3Wrapper.fromCredential(credentialProfile, "us-west-2")) { s3 =>
      assert(s3.doesBucketExists("beam"))
      assert(!s3.doesBucketExists("some-crazy-name-supposed-to-not-exist"))
    }
  }

  test("returns available buckets and its regions", Integration) {
    val expectedBucketsToExist = Set(
      S3Bucket("beam-admin-reports", "us-east-2"),
      S3Bucket("beam-builds", "us-east-2"),
      S3Bucket("beam-git-lfs", "us-east-2"),
      S3Bucket("beam-config", "us-west-2")
    )

    FileUtils.using(S3Wrapper.fromCredential(credentialProfile, "us-west-2")) { s3 =>
      val resultBuckets = s3.buckets

      assert(expectedBucketsToExist.subsetOf(resultBuckets))
    }
  }

  test("allBucketFiles from bucket [beam-config]", Integration) {
    val expectedKeysToExist = Set(
      "beamville/r5/tolls.dat",
      "sf-light/sf-light-10k.conf"
    )

    val bucketName = "beam-config"
    val allBucketFiles = FileUtils.using(S3Wrapper.fromCredential(credentialProfile)) { s3 =>
      s3.allBucketFiles(bucketName)
    }

    val allBucketKeys = allBucketFiles.map(_.key)

    assert(expectedKeysToExist.subsetOf(allBucketKeys))
  }

  test("download bucket directory", Integration) {
    val expectedFilesToExist = Set(
      Paths.get("beamville", "r5", "bus.zip"),
      Paths.get("beamville", "r5", "bus", "agency.txt"),
      Paths.get("beamville", "r5", "bus-freq", "agency.txt")
    )
    val bucketName = "beam-config"
    val keysStartingWith = "beamville/r5/bus"
    downloadAndAssertFilesHaveBeenDownloaded(expectedFilesToExist, bucketName, keysStartingWith)
  }

  private def downloadAndAssertFilesHaveBeenDownloaded(
    filesRelativePath: Set[Path],
    bucketName: String,
    keysStartingWith: String
  ): Assertion = {
    val tempDestination = Files.createTempDirectory("s3test-download-dir")
    val expectedFilesToExist = filesRelativePath.map(f => tempDestination.resolve(f))
    try {
      FileUtils.using(S3Wrapper.fromCredential(credentialProfile)) { s3 =>
        s3.download(bucketName, keysStartingWith, tempDestination)
      }

      val expectedFilesExistAsFiles = expectedFilesToExist.forall(p => Files.isRegularFile(p))
      assert(expectedFilesExistAsFiles, s"Files [$expectedFilesToExist] are supposed to have been downloaded")
    } finally {
      ApacheFileUtils.forceDelete(tempDestination.toFile)
    }
  }
}
