package beam.integration

import akka.actor.ActorSystem
import beam.agentsim.infrastructure.taz.CsvTaz
import beam.sim.{BeamHelper, BeamServices}
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.{ConfigConsistencyComparator, FileUtils, InputConsistencyCheck, LoggingUtil}
import org.scalatest.wordspec.AnyWordSpecLike
import beam.utils.TestConfigUtils.testConfig
import beam.utils.csv.CsvWriter
import beam.utils.matsim_conversion.{ConversionConfig, MatsimConversionTool}
import beam.utils.scenario.{HouseholdId, HouseholdInfo, VehicleInfo}
import beam.utils.scenario.generic.writers.CsvPersonInfoWriter.{headers, logger}
import beam.utils.scenario.urbansim.censusblock.entities.{
  Activity,
  Female,
  InputPersonInfo,
  InputPlanElement,
  Leg,
  Male
}
import ch.qos.logback.classic.util.ContextInitializer
import com.google.inject.Injector
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Coord
import org.matsim.core.network.NetworkUtils
import org.matsim.core.scenario.MutableScenario

import java.io.File
import java.nio.file.Paths
import scala.util.Random

class NetworkRelaxationScenarioGenerator {
  val scenarioDir = new File("test/input/relaxation-network-scenario/urbansim_v2")

  def generateHouseholds(count: Int = 2000) = {
    println(s"Generating $count households")
    val csvWriter = new CsvWriter(
      scenarioDir + "/households.csv",
      Seq("household_id", "income", "cars", "block_id")
    )
    try {
      for (i <- 1 to count) {
        csvWriter.write(i.toString, "0", "1", "1")
      }
    } finally {
      csvWriter.close()
    }

  }

  def generateVehicles(count: Int = 2000) = {
    println(s"Generating $count vehicles")
    val vehicles = for (i <- 1 to count) yield {
      VehicleInfo(i.toString, "car", None, i.toString)
    }
    val csvWriter = new CsvWriter(
      "test/input/relaxation-network-scenario/vehicles.csv",
      Seq("vehicleId", "vehicleTypeId", "stateOfCharge", "householdId")
    )
    try {
      vehicles.foreach { vehicle =>
        csvWriter.write(
          vehicle.vehicleId,
          vehicle.vehicleTypeId,
          vehicle.initialSoc,
          vehicle.householdId
        )
      }
    } finally {
      csvWriter.close()
    }
  }

  def generatePersons(count: Int = 2000) = {
    println(s"Generating $count persons")
    val persons = for (i <- 1 to count) yield {
      InputPersonInfo(
        personId = i.toString,
        householdId = i.toString,
        age = 20 + Random.nextInt(50),
        sex = if (Random.nextBoolean()) Male else Female
      )
    }
    val csvWriter = new CsvWriter(scenarioDir.getPath + "/persons.csv", Seq("person_id", "household_id", "age", "sex"))
    try {
      persons.foreach { person =>
        csvWriter.write(
          person.personId,
          person.householdId,
          person.age,
          person.sex match {
            case Male   => 1
            case Female => 2
          }
        )
      }
    } finally {
      csvWriter.close()
    }
  }

  def generatePlans(count: Int = 2000) = {
    println("Generating plans")
    val headers = Seq(
      "trip_id",
      "person_id",
      "PlanElementIndex",
      "ActivityElement",
      "trip_mode",
      "ActivityType",
      "x",
      "y",
      "departure_time"
    )
    val plans = for (i <- 1 to count) yield {
      // Home = -0.03763798759, 0.00975476975
      // Work = 0.04874384139, 0.01013286711

      // TODO wgs2utm =
      Seq(
        InputPlanElement(
          tripId = Some(i.toString),
          personId = i.toString,
          planElementIndex = 1,
          activityElement = Activity,
          tripMode = None,
          ActivityType = Some("Home"),
          x = Some(161827.4228835071),
          y = Some(1079.7224574150498),
          departureTime = Some(8)
        ),
        InputPlanElement(
          tripId = Some(i.toString),
          personId = i.toString,
          planElementIndex = 2,
          activityElement = Leg,
          tripMode = Some("CAR"),
          ActivityType = None,
          x = None,
          y = None,
          departureTime = None
        ),
        InputPlanElement(
          tripId = Some(i.toString),
          personId = i.toString,
          planElementIndex = 3,
          activityElement = Activity,
          tripMode = None,
          ActivityType = Some("Work"),
          x = Some(171452.789949885),
          y = Some(1121.4837267244413),
          departureTime = None
        )
      )
    }
    println(plans)

    val csvWriter = new CsvWriter(scenarioDir.getPath + "/plans.csv", headers)
    try {
      plans.flatten.foreach { plan =>
        csvWriter.write(
          plan.tripId.mkString,
          plan.personId.mkString,
          plan.planElementIndex,
          plan.activityElement,
          plan.tripMode,
          plan.ActivityType,
          plan.x,
          plan.y,
          plan.departureTime
        )
      }
    } finally {
      csvWriter.close()
    }
  }

  def generate() = {
//    generateHouseholds()
//    generateVehicles()
//    generatePersons()
    generatePlans()
  }
}

object NetworkRelaxationScenarioGenerator extends App {
  new NetworkRelaxationScenarioGenerator().generate()
}

class NetworkRelaxationSpec extends AnyWordSpecLike with BeamHelper {
  System.setProperty(ContextInitializer.CONFIG_FILE_PROPERTY, "logback-test.xml")

  "Network relaxation" should {
    "pass" in {
      val config = ConfigFactory
        .parseFile(new File("test/input/relaxation-network-scenario/beam.conf"))
        .resolve()
      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig = configBuilder.buildMatSimConf()
      val beamConfig = BeamConfig(config)

      val geoUtils = new GeoUtilsImpl(beamConfig)

      // Home = -0.03763798759, 0.00975476975
      // Work = 0.04874384139, 0.01013286711

      println(geoUtils.wgs2Utm(new Coord(-0.03763798759, 0.00975476975)))
      println(geoUtils.wgs2Utm(new Coord(0.04874384139, 0.01013286711)))

      val networkCoordinator = buildNetworkCoordinator(beamConfig)
      val boundingBox = NetworkUtils.getBoundingBox(networkCoordinator.network.getNodes.values())
      val minX = boundingBox(0)
      val maxX = boundingBox(2)
      val minY = boundingBox(1)
      val maxY = boundingBox(3)

      val midX = (maxX + minX) / 2
      val midY = (maxY + minY) / 2

//      println(new Coord(midX, midY))

      val (scenarioBuilt, beamScenario, _) = buildBeamServicesAndScenario(beamConfig, matsimConfig)
      val scenario: MutableScenario = scenarioBuilt
      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
      val injector: Injector = buildInjector(config, beamConfig, scenario, beamScenario)
      implicit val actorSystem: ActorSystem = injector.getInstance(classOf[ActorSystem])
      val beamServices: BeamServices = buildBeamServices(injector)

      LoggingUtil.initLogger(outputDir, beamConfig.beam.logger.keepConsoleAppenderOn)
      println(s"Beam output directory is: $outputDir")
      logger.info(ConfigConsistencyComparator.getMessage.getOrElse(""))

      val errors = InputConsistencyCheck.checkConsistency(beamConfig)
      if (errors.nonEmpty) {
        logger.error("Input consistency check failed:\n" + errors.mkString("\n"))
      }

      runBeam(
        beamServices,
        scenario,
        beamScenario,
        outputDir,
        true
      )
      fail
    }
  }
}
