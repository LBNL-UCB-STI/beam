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
      val results: Seq[SnapCoordinateUtils.Result] = scenario.getPopulation.getPersons
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

      results.forall {
        case _: Result.Succeed => true
        case _                 => false
      } shouldBe true
    }
  }

}
