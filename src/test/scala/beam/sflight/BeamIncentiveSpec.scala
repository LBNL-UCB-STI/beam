package beam.sflight

import scala.io.Source

import beam.analysis.plots.ModeChosenAnalysis
import beam.router.Modes.BeamMode
import beam.sflight.CaccSpec.NotFoundCarInTravelTimeMode
import beam.sim.BeamHelper
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.google.inject
import com.typesafe.config.ConfigFactory
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.must.Matchers

class BeamIncentiveSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

  private var injector: inject.Injector = _

  override def afterAll(): Unit = {
    val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
    if (travelDistanceStats != null)
      travelDistanceStats.close()
    super.afterAll()
  }

  "BeamVille with a lot of ride_hail incentives" must {
    "choose ride_hail more times than without/less incentives" in {
      val lastIteration = 0
      val avgChoicesWithoutRideHailIncentive =
        runSimulationAndCalculateAverageOfRideHailChoices(lastIteration, "incentives.csv")
      val avgChoicesWithRideHailIncentives =
        runSimulationAndCalculateAverageOfRideHailChoices(lastIteration, "incentives-ride_hail.csv")
      assert(avgChoicesWithoutRideHailIncentive <= avgChoicesWithRideHailIncentives)
    }
  }

  private def runSimulationAndCalculateAverageOfRideHailChoices(
    iterationNumber: Int,
    incentivesFile: String
  ): Double = {
    val beamVilleFolder = "test/input/beamville/"
    val config = ConfigFactory
      .parseString(
        s"""
            |beam.actorSystemName = "BeamIncentiveSpec"
            |beam.outputs.collectAndCreateBeamAnalysisAndGraphs=true
                      |beam.agentsim.lastIteration = $iterationNumber
            |beam.agentsim.agents.modeIncentive.filePath = "$beamVilleFolder$incentivesFile"
         """.stripMargin
      )
      .withFallback(testConfig(s"${beamVilleFolder}beam.conf"))
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

    val fileName = extractFileName(outputDir, iterationNumber)
    BeamIncentiveSpec.avgRideHailModeFromCsv(fileName)
  }

  private def extractFileName(
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

object BeamIncentiveSpec {

  def avgRideHailModeFromCsv(filePath: String): Double = {
    val carLine = FileUtils.using(Source.fromFile(filePath)) { source =>
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

  def isRideHail(value: String): Boolean = rideHailValues.exists(value.startsWith)

  private val rideHailValues = Set(BeamMode.RIDE_HAIL.value, BeamMode.RIDE_HAIL_POOLED.value)

}
