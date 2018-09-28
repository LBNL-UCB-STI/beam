package beam.sim

import java.io.IOException
import java.nio.file.{Files, Path, Paths}

import beam.integration.IntegrationSpecCommon
import beam.sim.BeamWarmStartSpec._
import beam.sim.config.BeamConfig
import com.typesafe.config.ConfigValueFactory
import org.apache.commons.io.FileUtils.getTempDirectoryPath
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class BeamWarmStartSpec
  extends WordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with IntegrationSpecCommon {

  //03045360156
  lazy val testDataPath: Path = Paths.get(getTempDirectoryPath, "warmStartTestData")

  override def beforeAll: Unit = {
    createDirs(testDataPath)
  }

  override def afterAll(): Unit = {
    deleteDir(testDataPath)
  }

  "from a flat directory, getWarmStartFilePath" must {
    "find plans and linkstats" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case1")
      createDirs(caseDataPath)
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      val expectedStats = copyLinkStats(caseDataPath, "linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz")

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "only find plans" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case2")
      createDirs(caseDataPath)
      val expectedPlans = None
      //copyPlans(caseDataPath, "output_plans")
      val expectedStats = copyLinkStats(caseDataPath, "linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz")

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "only find linkstats" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case3")
      createDirs(caseDataPath)
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      val expectedStats = None //copyLinkStats(caseDataPath, "linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz")

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "find plans and linkstats even files are 1 level deeper" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case1a")
      createDirs(Paths.get(caseDataPath.toString, "level1"))

      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "level1"), "output_plans")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "level1"), "linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz")

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "find plans and linkstats even files are 2 level deeper" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case1a")
      createDirs(Paths.get(caseDataPath.toString, "level1/level2"))

      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "level1/level2"), "output_plans")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "level1/level2"), "linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz")

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }
  }

  "from a parent run, getWarmStartFilePath" must {

    "find plan from run level and linkstats from iteration level" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case4")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.linkstats")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if link stat is not in last iteration" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case5")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if plans are not at root level" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case6")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))

      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.linkstats")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if plans are neither at root level nor in last iteration" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case7")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1/../it.2"))

      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.linkstats")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.linkstats")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.2"), "2.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if there are other files in iteration with name plan in it" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case8")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1/"))

      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.experienced_plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.experienced_plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.experienced_plans_scores")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.linkstats")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "prefer output_plan(root level plan) over the iteration" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case9")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.linkstats")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "prefer last iteration link stats over root" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case10")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(caseDataPath, "linkstats")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.linkstats")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "find out files, if run results are deeper at level" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case11")
      createDirs(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      copyPlans(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.0"), "0.linkstats")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.1"), "1.linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath("plans.xml.gz")
      val actualStats = warmStart.getWarmStartFilePath("linkstats.csv.gz", rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }
  }

  private def getWarmStart(casePath: Path): BeamWarmStart = {
    val conf = baseConfig.withValue("beam.warmStart.enabled", ConfigValueFactory.fromAnyRef(true))
      .withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef(casePath.toString)).resolve()
    BeamWarmStart(BeamConfig(conf))
  }
}

object BeamWarmStartSpec {
  def createDirs(path: Path): Unit = {
    try {
      Files.createDirectories(path)
      println(s"Dirs crated $path")
    } catch {
      case e: IOException =>
        println("Cannot create directories - " + e)
    }
  }

  def deleteDir(directoryToBeDeleted: Path): Boolean = {
    val allContents = directoryToBeDeleted.toFile.listFiles
    if (allContents != null) for (file <- allContents) {
      deleteDir(file.toPath)
    }
    directoryToBeDeleted.toFile.delete
  }

  def copyPlans(toDir: Path, asName: String): Option[String] = {
    val plansSrc = Paths.get("test/input/beamville/test-data/beamville.plans.xml.gz")
    val plansDest = Paths.get(toDir.toString, s"$asName.xml.gz")
    Files.copy(plansSrc, plansDest)
    println(s"File copied to $plansDest")
    Some(plansDest.toString)
  }

  def copyLinkStats(toDir: Path, asName: String): Option[String] = {
    val plansSrc = Paths.get("test/input/beamville/test-data/beamville.linkstats.csv.gz")
    val statsDest = Paths.get(toDir.toString, s"$asName.csv.gz")
    Files.copy(plansSrc, statsDest)
    println(s"File copied to $statsDest")
    Some(statsDest.toString)
  }
}
