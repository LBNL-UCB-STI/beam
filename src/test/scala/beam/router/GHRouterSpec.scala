package beam.router

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKitBase}
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.CAR
import beam.router.model.{BeamLeg, BeamPath, RoutingModel}
import beam.sflight.RouterForTest
import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{SimRunnerForTest, TestConfigUtils}
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.events.EventsUtils
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.vehicles.Vehicle
import org.scalatest.{BeforeAndAfterAll, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

import scala.language.postfixOps

class GHRouterSpec extends WordSpecLike with Matchers with BeamHelper {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        beam.routing.carRouter="staticGH"
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("GHRouterSpec", config)

  "Static GH" must {

    "run successfully" in {
      runBeamWithConfig(config)
    }
  }

}
