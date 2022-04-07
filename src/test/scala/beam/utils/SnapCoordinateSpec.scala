package beam.utils

import beam.sim.BeamHelper
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.utils.SnapCoordinateUtils.{Result, SnapLocationHelper}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.config.Config
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter

class SnapCoordinateSpec extends AnyWordSpec with Matchers with BeamHelper {

  val pwd: String = System.getenv("PWD")

  // TODO
  // 1. compare out put csv

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

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, beamScenario, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
        new GeoUtilsImpl(beamConfig),
        beamScenario.transportNetwork.streetLayer,
        beamConfig.beam.routing.r5.linkRadiusMeters
      )

      val dummyCoord = new Coord()
      val population: Seq[SnapCoordinateUtils.Result] = scenario.getPopulation.getPersons
        .values()
        .asScala
        .flatMap { person =>
          person.getPlans.asScala.flatMap { plan =>
            plan.getPlanElements.asScala.map {
              case e: Activity => snapLocationHelper.computeResult(e.getCoord)
              case _           => Result.Succeed(dummyCoord)
            }
          }
        }
        .toList

      val households: Seq[SnapCoordinateUtils.Result] = scenario.getHouseholds.getHouseholds
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

      (population ++ households).forall {
        case _: Result.Succeed => true
        case _                 => false
      } shouldBe true
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

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, _, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val households = scenario.getHouseholds.getHouseholds.values().asScala.toList

      scenario.getPopulation.getPersons.size() shouldBe 2
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

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, _, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      scenario.getPopulation.getPersons.size() shouldBe 1
      scenario.getHouseholds.getHouseholds.size() shouldBe 1
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

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, _, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val households = scenario.getHouseholds.getHouseholds.values().asScala.toList

      scenario.getPopulation.getPersons.size() shouldBe 2
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

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, _, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      scenario.getPopulation.getPersons.size() shouldBe 1
      scenario.getHouseholds.getHouseholds.size() shouldBe 1
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

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, _, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      val households = scenario.getHouseholds.getHouseholds.values().asScala.toList

      scenario.getPopulation.getPersons.size() shouldBe 2
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

      FileUtils.setConfigOutputFile(beamConfig, matsimConfig)

      val (scenario, _, _) = buildBeamServicesAndScenario(
        beamConfig,
        matsimConfig
      )

      scenario.getPopulation.getPersons.size() shouldBe 1
      scenario.getHouseholds.getHouseholds.size() shouldBe 1
    }

  }

}
