package beam.utils

import beam.agentsim.agents.freight.{FreightCarrier, FreightTour, PayloadPlan}
import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.SnapCoordinateUtils.{Category, CsvFile, Error, ErrorInfo, SnapCoordinateResult, SnapLocationHelper}
import beam.utils.TestConfigUtils.testConfig
import beam.utils.scenario.ScenarioLoaderHelper
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.{Activity, Population}
import org.matsim.core.config.Config
import org.matsim.households.Households
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.io.Source
import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter

class SnapCoordinateSpec extends AnyWordSpec with Matchers with BeamHelper {

  val pwd: String = System.getenv("PWD")

  def readErrorCsv(path: String): List[ErrorInfo] = {
    val src = Source.fromFile(path)
    val errors = src
      .getLines()
      .drop(1)
      .map(_.split(","))
      .map { row =>
        ErrorInfo(row(0), Category.withName(row(1)), Error.withName(row(2)), row(3).toDouble, row(4).toDouble)
      }
      .toList
    src.close()
    errors
  }

  def intersection(population: Population, path: String): Set[String] = {
    val invalidPersonIds = readErrorCsv(path).map(_.id).toSet
    val validPersonIds = population.getPersons.keySet().asScala.map(_.toString).toSet
    validPersonIds.intersect(invalidPersonIds)
  }

  def intersection(households: Households, path: String): Set[String] = {
    val invalidHouseholdIds = readErrorCsv(path).map(_.id).toSet
    val validHouseholdIds = households.getHouseholds.keySet().asScala.map(_.toString).toSet
    validHouseholdIds.intersect(invalidHouseholdIds)
  }

  def intersection(freightTours: Array[FreightTour], path: String): Set[String] = {
    val invalidIds = readErrorCsv(path).map(_.id).toSet
    val validIds = freightTours.map(_.tourId.toString).toSet
    validIds.intersect(invalidIds)
  }

  def intersection(freightPayloadPlans: Array[PayloadPlan], path: String): Set[String] = {
    val invalidIds = readErrorCsv(path).map(_.id).toSet
    val validIds = freightPayloadPlans.map(_.payloadId.toString).toSet
    validIds.intersect(invalidIds)
  }

  def intersection(freightCarriers: Array[FreightCarrier], path: String): Set[String] = {
    val invalidIds = readErrorCsv(path).map(_.id).toSet
    val validIds = freightCarriers.map(_.carrierId.toString).toSet
    validIds.intersect(invalidIds)
  }

  "scenario plan" should {
    "contain all valid locations" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString("""
                       |beam.routing.r5.linkRadiusMeters = 350
                       |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )
      ScenarioLoaderHelper.validateScenario(scenario, snapLocationHelper, Some(outputDir))

      val dummyCoord = new Coord()
      val population: Seq[SnapCoordinateResult] = scenario.getPopulation.getPersons
        .values()
        .asScala
        .flatMap { person =>
          person.getPlans.asScala.flatMap { plan =>
            plan.getPlanElements.asScala.map {
              case e: Activity => snapLocationHelper.computeResult(e.getCoord)
              case _           => Right(dummyCoord)
            }
          }
        }
        .toList

      val households: Seq[SnapCoordinateResult] = scenario.getHouseholds.getHouseholds
        .values()
        .asScala
        .map { household =>
          val locationX = scenario.getHouseholds.getHouseholdAttributes
            .getAttribute(household.getId.toString, "homecoordx")
            .asInstanceOf[Double]
          val locationY = scenario.getHouseholds.getHouseholdAttributes
            .getAttribute(household.getId.toString, "homecoordy")
            .asInstanceOf[Double]
          val coord = new Coord(locationX, locationY)
          snapLocationHelper.computeResult(coord)
        }
        .toList

      (population ++ households).forall(_.isRight) shouldBe true
    }

    "remove invalid persons and households [case1 xml input]" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.agents.plans {
                        |  inputPlansFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case1/xml/population.xml"
                        |  inputPersonAttributesFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case1/xml/populationAttributes.xml"
                        |}
                        |beam.agentsim.agents.households {
                        |  inputFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case1/xml/households.xml"
                        |  inputHouseholdAttributesFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case1/xml/householdAttributes.xml"
                        |}
                        |beam.routing.r5.linkRadiusMeters = 350
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )
      ScenarioLoaderHelper.validateScenario(scenario, snapLocationHelper, Some(outputDir))

      val households = scenario.getHouseholds.getHouseholds.values().asScala.toList

      scenario.getPopulation.getPersons.size() shouldBe 2
      intersection(scenario.getPopulation, path = s"$outputDir/${CsvFile.Plans}") shouldBe Set.empty

      households.foreach { household =>
        household.getMemberIds.size() shouldBe 1
      }
    }

    "remove invalid persons and households [case2 xml input]" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.agents.plans {
                        |  inputPlansFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case2/xml/population.xml"
                        |  inputPersonAttributesFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case2/xml/populationAttributes.xml"
                        |}
                        |beam.agentsim.agents.households {
                        |  inputFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case2/xml/households.xml"
                        |  inputHouseholdAttributesFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case2/xml/householdAttributes.xml"
                        |}
                        |beam.routing.r5.linkRadiusMeters = 350
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )
      ScenarioLoaderHelper.validateScenario(scenario, snapLocationHelper, Some(outputDir))

      scenario.getPopulation.getPersons.size() shouldBe 1
      intersection(scenario.getPopulation, path = s"$outputDir/${CsvFile.Plans}") shouldBe Set.empty

      scenario.getHouseholds.getHouseholds.size() shouldBe 1
      intersection(scenario.getHouseholds, path = s"$outputDir/${CsvFile.Households}") shouldBe Set.empty
    }

    "remove invalid persons and households [case1 csv input]" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.agents.plans.inputPlansFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case1/csv/plans.csv"
                        |beam.agentsim.agents.households.inputFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case1/csv/households.csv"
                        |beam.routing.r5.linkRadiusMeters = 350
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam-csv.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )
      ScenarioLoaderHelper.validateScenario(scenario, snapLocationHelper, Some(outputDir))

      val households = scenario.getHouseholds.getHouseholds.values().asScala.toList

      scenario.getPopulation.getPersons.size() shouldBe 2
      intersection(scenario.getPopulation, path = s"$outputDir/${CsvFile.Plans}") shouldBe Set.empty

      households.foreach { household =>
        household.getMemberIds.size() shouldBe 1
      }
    }

    "remove invalid persons and households [case2 csv input]" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.agents.plans.inputPlansFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case2/csv/plans.csv"
                        |beam.agentsim.agents.households.inputFilePath = "$pwd/test/test-resources/beam/input/snap-location/scenario/case2/csv/households.csv"
                        |beam.routing.r5.linkRadiusMeters = 350
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam-csv.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )
      ScenarioLoaderHelper.validateScenario(scenario, snapLocationHelper, Some(outputDir))

      scenario.getPopulation.getPersons.size() shouldBe 1
      intersection(scenario.getPopulation, path = s"$outputDir/${CsvFile.Plans}") shouldBe Set.empty

      scenario.getHouseholds.getHouseholds.size() shouldBe 1
      intersection(scenario.getHouseholds, path = s"$outputDir/${CsvFile.Households}") shouldBe Set.empty
    }

    "remove invalid persons and households [case1 urbansimv2 input]" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.exchange.scenario.folder = "$pwd/test/test-resources/beam/input/snap-location/scenario/case1/urbansim_v2"
                        |beam.routing.r5.linkRadiusMeters = 350
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam-urbansimv2.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )
      ScenarioLoaderHelper.validateScenario(scenario, snapLocationHelper, Some(outputDir))

      val households = scenario.getHouseholds.getHouseholds.values().asScala.toList

      scenario.getPopulation.getPersons.size() shouldBe 2
      intersection(scenario.getPopulation, path = s"$outputDir/${CsvFile.Plans}") shouldBe Set.empty

      households.foreach { household =>
        household.getMemberIds.size() shouldBe 1
      }
    }

    "remove invalid persons and households [case2 urbansimv2 input]" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.exchange.scenario.folder = "$pwd/test/test-resources/beam/input/snap-location/scenario/case2/urbansim_v2"
                        |beam.routing.r5.linkRadiusMeters = 350
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam-urbansimv2.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )
      ScenarioLoaderHelper.validateScenario(scenario, snapLocationHelper, Some(outputDir))

      scenario.getPopulation.getPersons.size() shouldBe 1
      intersection(scenario.getPopulation, path = s"$outputDir/${CsvFile.Plans}") shouldBe Set.empty

      scenario.getHouseholds.getHouseholds.size() shouldBe 1
      intersection(scenario.getHouseholds, path = s"$outputDir/${CsvFile.Households}") shouldBe Set.empty
    }

    "remove invalid freight tours" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.agents.freight.toursFilePath = "$pwd/test/test-resources/beam/input/snap-location/freight/freight-tours.csv"
                        |beam.routing.r5.linkRadiusMeters = 350
                        |beam.agentsim.snapLocationAndRemoveInvalidInputs = true
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam-freight.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (_, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      intersection(
        beamScenario.freightCarriers.flatMap(_.tourMap.values).flatten.toArray,
        path = s"$outputDir/${CsvFile.FreightTours}"
      ) shouldBe Set.empty
    }

    "remove invalid freight payload plans" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.agents.freight.plansFilePath = "$pwd/test/test-resources/beam/input/snap-location/freight/payload-plans.csv"
                        |beam.routing.r5.linkRadiusMeters = 350
                        |beam.agentsim.snapLocationAndRemoveInvalidInputs = true
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam-freight.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (_, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      intersection(
        beamScenario.freightCarriers.flatMap(_.payloadPlans.values).toArray,
        path = s"$outputDir/${CsvFile.FreightPayloadPlans}"
      ) shouldBe Set.empty
    }

    "remove invalid freight carriers" in {
      lazy val config: TypesafeConfig = ConfigFactory
        .parseString(s"""
                        |beam.agentsim.agents.freight.carriersFilePath = "$pwd/test/test-resources/beam/input/snap-location/freight/freight-carriers.csv"
                        |beam.routing.r5.linkRadiusMeters = 350
                        |beam.agentsim.snapLocationAndRemoveInvalidInputs = true
                        |""".stripMargin)
        .withFallback(testConfig("test/input/beamville/beam-freight.conf"))
        .resolve()

      val configBuilder = new MatSimBeamConfigBuilder(config)
      val matsimConfig: Config = configBuilder.buildMatSimConf()
      matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
      val beamConfig: BeamConfig = BeamConfig(config)

      val outputDir = FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (_, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      intersection(
        beamScenario.freightCarriers.toArray,
        path = s"$outputDir/${CsvFile.FreightCarriers}"
      ) shouldBe Set.empty
    }
  }

}
