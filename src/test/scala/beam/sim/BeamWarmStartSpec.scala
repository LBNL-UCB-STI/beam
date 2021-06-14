package beam.sim

import java.io.IOException
import java.nio.file.{Files, Path, Paths}

import beam.integration.IntegrationSpecCommon
import beam.sim.BeamWarmStartSpec._
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.PopulationScaling
import beam.utils.FileUtils
import com.typesafe.config.ConfigValueFactory
import org.apache.commons.io.FileUtils.getTempDirectoryPath
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.slf4j.LoggerFactory

class BeamWarmStartSpec
    extends AnyWordSpecLike
    with Matchers
    with BeforeAndAfterAll
    with IntegrationSpecCommon
    with BeamHelper {

  lazy val testDataPath: Path = Paths.get(getTempDirectoryPath, "warmStartTestData")

  override def beforeAll: Unit = {
    deleteDir(testDataPath)
    createDirs(testDataPath)
  }

  override def afterAll(): Unit = {
    deleteDir(testDataPath)
  }

  "from a flat directory, getWarmStartFilePath" must {
    "find plans and linkstats" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case1")
      createDirs(caseDataPath)
      val expectedPlans = copyPlans(caseDataPath, OUTPUT_PLANS)
      val expectedStats = copyLinkStats(caseDataPath, LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "only find plans" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case2")
      createDirs(caseDataPath)
      val expectedPlans = copyPlans(caseDataPath, "output_plans")
      val expectedStats = None // copyLinkStats(caseDataPath, LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "only find linkstats" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case3")
      createDirs(caseDataPath)
      val expectedPlans = None //copyPlans(caseDataPath, OUTPUT_PLANS)
      val expectedStats = copyLinkStats(caseDataPath, "linkstats")

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "find plans and linkstats even files are 1 level deeper" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case1a")
      createDirs(Paths.get(caseDataPath.toString, "level1"))

      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "level1"), OUTPUT_PLANS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "level1"), LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "find plans and linkstats even files are 2 level deeper" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case1b")
      createDirs(Paths.get(caseDataPath.toString, "level1/level2"))

      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "level1/level2"), OUTPUT_PLANS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "level1/level2"), LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }
  }

  "from a parent run, getWarmStartFilePath" must {

    "find plan from run level and linkstats from iteration level" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case4")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, OUTPUT_PLANS)
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0." + LINK_STATS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if link stat is not in last iteration" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case5")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, OUTPUT_PLANS)
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if plans are not at root level" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case6")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))

      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0." + LINK_STATS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if plans are neither at root level nor in last iteration" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case7")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1/../it.2"))

      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0." + LINK_STATS)
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1." + LINK_STATS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.2"), "2." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "work if there are other files in iteration with plan in their names" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case8")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1/"))

      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.experiencedPlans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.experiencedPlans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.experiencedPlansScores")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      val expectedPlans = copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0." + LINK_STATS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "prefer output_plan(root level plan) over the iteration" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case9")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, OUTPUT_PLANS)
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0." + LINK_STATS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "prefer last iteration link stats over root" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case10")
      createDirs(Paths.get(caseDataPath.toString, "/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, OUTPUT_PLANS)
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1.plans")
      copyLinkStats(caseDataPath, LINK_STATS)
      copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.0"), "0." + LINK_STATS)
      val expectedStats = copyLinkStats(Paths.get(caseDataPath.toString, "/ITERS/it.1"), "1." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }

    "find out files, if run results are at deeper level" in {
      val caseDataPath = Paths.get(testDataPath.toString, "case11")
      createDirs(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.0/../it.1"))
      val expectedPlans = copyPlans(caseDataPath, OUTPUT_PLANS)
      copyPlans(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.0"), "0.plans")
      copyPlans(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.1"), "1.plans")
      copyLinkStats(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.0"), "0." + LINK_STATS)
      val expectedStats =
        copyLinkStats(Paths.get(caseDataPath.toString, "level1/level2/level3/level4/ITERS/it.1"), "1." + LINK_STATS)

      val warmStart: BeamWarmStart = getWarmStart(caseDataPath)

      val actualPlans = warmStart.getWarmStartFilePath(PLANS_GZ)
      val actualStats = warmStart.getWarmStartFilePath(LINK_STATS_GZ, rootFirst = false)

      actualPlans shouldEqual expectedPlans
      actualStats shouldEqual expectedStats
    }
  }

  private def getWarmStart(casePath: Path): BeamWarmStart = {
    val conf = baseConfig
      .withValue("beam.warmStart.type", ConfigValueFactory.fromAnyRef("full"))
      .withValue("beam.warmStart.path", ConfigValueFactory.fromAnyRef(casePath.toString))
      .resolve()
    BeamWarmStart(BeamConfig(conf), 30)
  }

  "Warmstart" must {

    "sample population when sampling enabled" in {
      loadScenarioAndGetPopulationSize(0.5, true, 1) < 30 should be(true)
    }

    "not sample population when sampling disabled" in {
      loadScenarioAndGetPopulationSize(0.5, true, 0) shouldBe (50)
    }

    "should not impact population sampling when warmstart disabled" in {
      loadScenarioAndGetPopulationSize(0.5, false, 0) < 30 should be(true)
      loadScenarioAndGetPopulationSize(0.5, false, 1) < 30 should be(true)
    }
  }

  private def loadScenarioAndGetPopulationSize(
    agentSampleSizeAsFractionOfPopulation: Double,
    warmstartEnabled: Boolean,
    samplePopulationIntegerFlag: Int
  ): Int = {
    val beamConfig = BeamConfig(
      baseConfig
        .withValue(
          "beam.agentsim.agentSampleSizeAsFractionOfPopulation",
          ConfigValueFactory.fromAnyRef(agentSampleSizeAsFractionOfPopulation)
        )
        .withValue("beam.warmStart.type", ConfigValueFactory.fromAnyRef(if (warmstartEnabled) "full" else "disabled"))
        .withValue(
          "beam.warmStart.samplePopulationIntegerFlag",
          ConfigValueFactory.fromAnyRef(samplePopulationIntegerFlag)
        )
        .resolve()
    )
    val beamScenario = loadScenario(beamConfig)
    val configBuilder = new MatSimBeamConfigBuilder(baseConfig)
    val matsimConfig = configBuilder.buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      module(baseConfig, beamConfig, scenario, beamScenario)
    )

    val beamServices: BeamServices = injector.getInstance(classOf[BeamServices])

    // during sampling the household vehicles are written out, which is not relevant here (providing temporary folder for it)
    val temporaryOutputDirectory = Files.createTempDirectory("dummyOutputDirectory").toString
    PopulationScaling.samplePopulation(scenario, beamScenario, beamConfig, beamServices, temporaryOutputDirectory)

    scenario.getPopulation.getPersons.size()
  }
}

object BeamWarmStartSpec {
  private val logger = LoggerFactory.getLogger(BeamWarmStartSpec.getClass)
  private val OUTPUT_PLANS = "output_plans"
  private val LINK_STATS = "linkstats"
  private val PLANS_GZ = "plans.xml.gz"
  private val LINK_STATS_GZ = "linkstats.csv.gz"

  def createDirs(path: Path): Unit = {
    try {
      Files.createDirectories(path)

    } catch {
      case e: IOException =>
        logger.error("Cannot create directories.", e)
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

    Some(plansDest.toString)
  }

  def copyLinkStats(toDir: Path, asName: String): Option[String] = {
    val plansSrc = Paths.get("test/input/beamville/test-data/beamville.linkstats.csv.gz")
    val statsDest = Paths.get(toDir.toString, s"$asName.csv.gz")
    Files.copy(plansSrc, statsDest)

    Some(statsDest.toString)
  }
}
