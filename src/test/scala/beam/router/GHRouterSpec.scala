package beam.router

import akka.actor.ActorSystem
import akka.pattern.ask
import akka.testkit.TestActorRef
import akka.util.Timeout
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.{Config, ConfigFactory}
import org.matsim.api.core.v01.Id
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.concurrent.duration._
import scala.language.postfixOps

class GHRouterSpec extends AnyWordSpecLike with Matchers with BeamHelper with ScalaFutures {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
         |beam.actorSystemName = "GHRouterSpec"
         |beam.routing.carRouter="staticGH"
      """.stripMargin
    )
    .withFallback(testConfig("beam.sim.test/input/beamville/beam.conf"))
    .resolve()

  lazy val configAltRoutes: Config = ConfigFactory
    .parseString("beam.routing.gh.useAlternativeRoutes = true")
    .withFallback(config)
    .resolve()

  "Static GH" must {
    "run successfully" in {
      runBeamWithConfig(config)
    }

    "add alternative route for GraphHopper if enabled" in {
      lazy implicit val system: ActorSystem = ActorSystem("GHRouterSpec", configAltRoutes)
      val (_, _, beamScenario: BeamScenario, services: BeamServices, _) = prepareBeamService(configAltRoutes, None)
      val transportNetwork = services.injector.getInstance(classOf[TransportNetwork])

      val request = RoutingRequest(
        originUTM = new Location(167138.0, 2234.0),
        destinationUTM = new Location(166321.9, 1568.87),
        departureTime = 36952,
        withTransit = false,
        personId = Some(Id.createPersonId(2)),
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId(2),
            Id.create("beamVilleCar", classOf[BeamVehicleType]),
            SpaceTime(167138.00001152378, 2233.9999999985166, 32482),
            BeamMode.CAR,
            asDriver = true,
            needsToCalculateCost = true
          ),
          StreetVehicle(
            Id.createVehicleId("body-2"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            SpaceTime(167138.0, 2234.0, 36952),
            BeamMode.WALK,
            asDriver = true,
            needsToCalculateCost = false
          )
        ),
        attributesOfIndividual = Some(
          AttributesOfIndividual(
            HouseholdAttributes("1", 50000.0, 3, 1, 0),
            modalityStyle = None,
            isMale = true,
            List(
              CAR,
              CAV,
              WALK,
              BIKE,
              TRANSIT,
              RIDE_HAIL,
              RIDE_HAIL_POOLED,
              RIDE_HAIL_TRANSIT,
              DRIVE_TRANSIT,
              WALK_TRANSIT,
              BIKE_TRANSIT
            ),
            12.254901960784315,
            Some(0),
            Some(0.0)
          )
        ),
        triggerId = 1
      )
      val worker =
        TestActorRef(
          RoutingWorker.props(
            beamScenario,
            transportNetwork,
            beamScenario.networks2,
            services.networkHelper,
            services.fareCalculator,
            services.tollCalculator
          )
        )

      implicit val timeout: Timeout = 1 minute
      val future = (worker ? request).mapTo[RoutingResponse]
      val response: RoutingResponse = future.futureValue
      response.itineraries.count(_.router.contains("GH")) shouldBe 3
    }

  }

}
