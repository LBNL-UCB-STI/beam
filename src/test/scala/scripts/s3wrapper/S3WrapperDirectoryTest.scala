package scripts.s3wrapper

import org.scalatest.FunSuite

class S3WrapperDirectoryTest extends FunSuite {

  test("find directory can find itself") {
    val dir = S3RemoteDirectory("key/")
    val f = dir.findResource("key/")
    assertResult(Some(dir))(f)
  }

  test("find file in root folder") {
    val dir = S3RemoteDirectory("key/", Seq(S3RemoteFile("key/xyz.txt", 0), S3RemoteFile("key/someFile.txt", 0)))
    val f = dir.findResource("key/someFile.txt")

    assertResult(Some(S3RemoteFile("key/someFile.txt", 0)))(f)
  }

  test("find subdirectory in second level") {
    val subdir = S3RemoteDirectory("root/b/", Seq(S3RemoteFile("root/b/b1.txt", 0), S3RemoteFile("root/b/b2.txt", 0)))
    val rootDir = S3RemoteDirectory("root/", Seq(S3RemoteFile("root/xyz.txt", 0), subdir, S3RemoteFile("root/someFile.txt", 0)))
    val f = rootDir.findResource("root/b/")

    assertResult(Some(subdir))(f)
  }

  test("find subdirectory in third level") {
    val subDirL1bL2 = S3RemoteDirectory("root/b/c/", Seq(S3RemoteFile("root/b/c/c1.txt", 0), S3RemoteFile("root/b/c/c2.txt", 0)))
    val subDirL1x = S3RemoteDirectory("root/x/", Seq(S3RemoteFile("root/x/x1.txt", 0), S3RemoteFile("root/x/x2.txt", 0)))
    val subDirL1b = S3RemoteDirectory("root/b/", Seq(S3RemoteFile("root/b/b1.txt", 0), S3RemoteFile("root/b/b2.txt", 0), subDirL1bL2))
    val rootDir = S3RemoteDirectory("root/", Seq(subDirL1b, S3RemoteFile("root/xyz.txt", 0), subDirL1x, S3RemoteFile("root/someFile.txt", 0)))

    val f = rootDir.findResource("root/b/c/")

    assertResult(Some(subDirL1bL2))(f)
  }

  test("find file in third level") {
    val fileC2 = S3RemoteFile("root/b/c/c2.txt", 0)
    val subDirL1bL2 = S3RemoteDirectory("root/b/c/", Seq(S3RemoteFile("root/b/c/c1.txt", 0), fileC2))
    val subDirL1x = S3RemoteDirectory("root/x/", Seq(S3RemoteFile("root/x/x1.txt", 0), S3RemoteFile("root/x/x2.txt", 0)))
    val subDirL1b = S3RemoteDirectory("root/b/", Seq(S3RemoteFile("root/b/b1.txt", 0), S3RemoteFile("root/b/b2.txt", 0), subDirL1bL2))
    val rootDir = S3RemoteDirectory("root/", Seq(subDirL1b, S3RemoteFile("root/xyz.txt", 0), subDirL1x, S3RemoteFile("root/someFile.txt", 0)))

    val f = rootDir.findResource("root/b/c/c2.txt")

    assertResult(Some(fileC2))(f)
  }

  test("extractKeys recursively") {
    val fileC2 = S3RemoteFile("root/b/c/c2.txt", 0)
    val subDirL1bL2 = S3RemoteDirectory("root/b/c/", Seq(S3RemoteFile("root/b/c/c1.txt", 0), fileC2))
    val subDirL1x = S3RemoteDirectory("root/x/", Seq(S3RemoteFile("root/x/x1.txt", 0), S3RemoteFile("root/x/x2.txt", 0)))
    val subDirL1b = S3RemoteDirectory("root/b/", Seq(S3RemoteFile("root/b/b1.txt", 0), S3RemoteFile("root/b/b2.txt", 0), subDirL1bL2))
    val rootDir = S3RemoteDirectory("root/", Seq(subDirL1b, S3RemoteFile("root/xyz.txt", 0), subDirL1x, S3RemoteFile("root/someFile.txt", 0)))

    val expectedKeys = Set(
      "root/",
      "root/someFile.txt",
      "root/xyz.txt",
      "root/x/",
      "root/x/x2.txt",
      "root/x/x1.txt",
      "root/b/",
      "root/b/b1.txt",
      "root/b/b2.txt",
      "root/b/c/",
      "root/b/c/c1.txt",
      "root/b/c/c2.txt",
    )

    assertResult(expectedKeys)(rootDir.allKeysRecursively)
  }

}
