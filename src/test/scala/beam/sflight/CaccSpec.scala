package beam.sflight

import scala.io.Source

import beam.analysis.plots.PersonTravelTimeAnalysis
import beam.router.Modes.BeamMode
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

class CaccSpec extends WordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

  private var injector: inject.Injector = _

  override def afterAll(): Unit = {
    val travelDistanceStats = injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats])
    if (travelDistanceStats != null)
      travelDistanceStats.close()
    super.afterAll()
  }

  private def runSimulationAndReturnAvgCarTravelTimes(caccEnabled: Boolean, iterationNumber: Int): Double = {
    val config = ConfigFactory
      .parseString(s"""
                     |beam.outputs.events.fileOutputFormats = xml
                     |beam.agentsim.lastIteration = $iterationNumber
                     |beam.physsim.jdeqsim.cacc.enabled = $caccEnabled
                     |beam.physsim.jdeqsim.cacc.minSpeedMetersPerSec = 0
                     |beam.agentsim.agents.vehicles.vehiclesFilePath = $${beam.inputDirectory}"/sample/1k/vehicles-cav.csv"
                   """.stripMargin)
      .withFallback(testConfig("test/input/sf-light/sf-light-1k.conf"))
      .resolve()

    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
    matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)

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
    CaccSpec.avgCarModeFromCsv(fileName)
  }

  private def extractFileName(
    matsimConfig: Config,
    beamConfig: BeamConfig,
    outputDir: String,
    iterationNumber: Int
  ): String = {
    val outputDirectoryHierarchy =
      new OutputDirectoryHierarchy(outputDir, OutputDirectoryHierarchy.OverwriteFileSetting.overwriteExistingFiles)

    outputDirectoryHierarchy.getIterationFilename(iterationNumber, PersonTravelTimeAnalysis.fileBaseName + ".csv")
  }

  "SF Light" must {
    "run 1k scenario car averageTravelTimes(deqsim.cacc.enabled=true) <= averageTravelTimes(deqsim.cacc.enabled=false)" taggedAs (Periodic, ExcludeRegular) in {
      val iteration = 1
      val avgWithCaccEnabled = runSimulationAndReturnAvgCarTravelTimes(caccEnabled = true, iteration)
      val avgWithCaccDisabled = runSimulationAndReturnAvgCarTravelTimes(caccEnabled = false, iteration)

      assert(avgWithCaccEnabled <= avgWithCaccDisabled)
    }
  }

}

object CaccSpec {

  def avgCarModeFromCsv(filePath: String): Double = {
    val carLine = FileUtils.using(Source.fromFile(filePath)) { source =>
      source.getLines().find(isCar)
    }

    val allHourAvg = carLine
      .getOrElse(throw NotFoundCarInTravelTimeMode)
      .split(",")
      .tail
      .map(_.toDouble)

    val relevantTimes = allHourAvg.filterNot(_ == 0D)
    relevantTimes.sum / relevantTimes.length
  }

  def isCar(value: String): Boolean = {
    carTravelTimeValues.exists(value.startsWith)
  }

  private val carTravelTimeValues = Set(BeamMode.CAR.value, BeamMode.CAV.value)

  case object NotFoundCarInTravelTimeMode
      extends IllegalStateException(
        s"The line does not contain ${carTravelTimeValues.mkString("'", "', '", "'")} as TravelTimeMode"
      )

}
