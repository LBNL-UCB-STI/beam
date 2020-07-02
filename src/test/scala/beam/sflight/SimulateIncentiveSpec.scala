package beam.sflight

import scala.io.Source

import beam.analysis.plots.ModeChosenAnalysis
import beam.router.Modes.BeamMode
import beam.sflight.CaccSpec.NotFoundCarInTravelTimeMode
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.tags.{ExcludeRegular, Periodic}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.google.inject
import com.typesafe.config.ConfigFactory
import org.matsim.core.config.Config
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}

class SimulateIncentiveSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

  private var injector: inject.Injector = _

  override def afterAll(): Unit = {
    val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
    if (travelDistanceStats != null)
      travelDistanceStats.close()
    super.afterAll()
  }

  "SF Light" must {
    "run 1k scenario with different incentives" taggedAs (Periodic, ExcludeRegular) in {
      val lastIteration = 0
      val avg1 = runSimulationAndReturnModeChoiceColumnSum(lastIteration, "incentives.csv")
      val avg2 = runSimulationAndReturnModeChoiceColumnSum(lastIteration, "incentives-ride_hail.csv")
      assert(avg1 <= avg2)
    }
  }

  private def runSimulationAndReturnModeChoiceColumnSum(iterationNumber: Int, incentivesFile: String): Double = {
    val sfLightFolder = "test/input/sf-light/"
    val config = ConfigFactory
      .parseString(s"""
                      |beam.agentsim.lastIteration = $iterationNumber
                      |beam.agentsim.agents.modeIncentive.filePath = "$sfLightFolder$incentivesFile"
                   """.stripMargin)
      .withFallback(testConfig(s"${sfLightFolder}sf-light-1k.conf"))
      .resolve()

    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()

    val beamConfig = BeamConfig(config)

    val outputDir: String = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

    val beamScenario = loadScenario(beamConfig)

    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)

    injector = buildInjector(config, beamConfig, scenario, beamScenario)
    val services = buildBeamServices(injector, scenario)
    DefaultPopulationAdjustment(services).update(scenario)

    val controller = services.controler
    controller.run()

    val fileName = extractFileName(matsimConfig, beamConfig, outputDir, iterationNumber)
    println(fileName)
    SimulateIncentiveSpec.avgRideHailModeFromCsv(fileName)
  }

  private def extractFileName(
    matsimConfig: Config,
    beamConfig: BeamConfig,
    outputDir: String,
    iterationNumber: Int
  ): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)

    outputDirectoryHierarchy.getIterationFilename(
      iterationNumber,
      ModeChosenAnalysis.getModeChoiceFileBaseName + ".csv"
    )
  }

}

object SimulateIncentiveSpec {

  def avgRideHailModeFromCsv(filePath: String): Double = {
    val carLine = using(Source.fromFile(filePath)) { source =>
      source.getLines().find(isRideHail)
    }

    val allHourAvg = carLine
      .getOrElse(throw NotFoundCarInTravelTimeMode)
      .split(",")
      .tail
      .map(_.toDouble)

    val relevantTimes = allHourAvg.filterNot(_ == 0D)
    relevantTimes.sum / relevantTimes.length
  }

  def isRideHail(value: String): Boolean = {
    rideHailValues.exists(value.startsWith)
  }

  private val rideHailValues = Set(BeamMode.RIDE_HAIL.value, BeamMode.RIDE_HAIL_POOLED.value)

  def using[A <: AutoCloseable, B](resource: A)(f: A => B): B = {
    try {
      f(resource)
    } finally {
      resource.close()
    }
  }

}
