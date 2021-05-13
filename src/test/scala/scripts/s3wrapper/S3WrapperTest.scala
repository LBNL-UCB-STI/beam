/* package scripts.s3wrapper

import beam.tags.Integration
import beam.utils.FileUtils
import org.apache.commons.io.{FileUtils => ApacheFileUtils}
import org.scalatest.{Assertion, FunSuite, Ignore}

import java.nio.file.{Files, Path, Paths}

@Ignore
class S3WrapperTest extends FunSuite {

  test("buckets returns available buckets and its regions", Integration) {
    val expectedBucketsToExist = Set(
      S3Bucket("beam-admin-reports", "us-east-2"),
      S3Bucket("beam-builds", "us-east-2"),
      S3Bucket("beam-git-lfs", "us-east-2"),
      S3Bucket("beam-config", "us-west-2")
    )

    FileUtils.using(S3Wrapper.fromCredential("beam", "us-west-2")) { s3 =>
      val resultBuckets = s3.buckets

      assert(expectedBucketsToExist.subsetOf(resultBuckets))
    }
  }

  test("allBucketFiles from bucket [beam-config]", Integration) {
    val expectedKeysToExist = Set(
      "beamville/r5/tolls.dat",
      "sf-light/sf-light-10k.conf",
    )

    val bucketName = "beam-config"
    val allBucketFiles = FileUtils.using(S3Wrapper.fromCredential("beam")) { s3 =>
      s3.allBucketFiles(bucketName)
    }

    val allBucketKeys = allBucketFiles.map(_.key)

    assert(expectedKeysToExist.subsetOf(allBucketKeys))
  }

  test("download bucket directory as long as is setup in the ~/.aws/credentials file", Integration) {
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
      FileUtils.using(S3Wrapper.fromCredential("beam")) { s3 =>
        s3.download(bucketName, keysStartingWith, tempDestination)
      }

      val expectedFilesExistAsFiles = expectedFilesToExist.forall(p => Files.isRegularFile(p))
      assert(expectedFilesExistAsFiles, s"Files [$expectedFilesToExist] are supposed to have been downloaded")
    } finally {
      ApacheFileUtils.forceDelete(tempDestination.toFile)
    }
  }
}
*/
