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
import org.scalatest.BeforeAndAfterAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.language.postfixOps

class TimeDependentRoutingSpec
    extends AnyWordSpecLike
    with TestKitBase
    with Matchers
    with ImplicitSender
    with SimRunnerForTest
    with RouterForTest
    with BeforeAndAfterAll {

  lazy val config: Config = ConfigFactory
    .parseString(
      """
        akka.log-dead-letters = 10
        akka.actor.debug.fsm = true
        akka.loglevel = debug
        """
    )
    .withFallback(testConfig("test/input/beamville/beam.conf"))
    .resolve()

  lazy implicit val system: ActorSystem = ActorSystem("TimeDependentRoutingSpec", config)

  override def outputDirPath: String = TestConfigUtils.testOutputDir

  "A time-dependent router" must {
    val origin = new BeamRouter.Location(166321.9, 1568.87)
    val destination = new BeamRouter.Location(167138.4, 1117)
    val time = 3000

    "give updated travel times for a given route" in {
      val leg = BeamLeg(
        3000,
        BeamMode.CAR,
        0,
        BeamPath(
          Vector(143, 60, 58, 62, 80, 74, 68, 154),
          Vector(),
          None,
          SpaceTime(services.geo.utm2Wgs(origin), 3000),
          SpaceTime(services.geo.utm2Wgs(destination), 3000),
          0.0
        )
      )
      router ! EmbodyWithCurrentTravelTime(
        leg,
        Id.createVehicleId(1),
        Id.create("beamVilleCar", classOf[BeamVehicleType]),
        triggerId = 0
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.head.beamLegs.head.duration == 147)
      // R5 travel time, but less than what's in R5's routing response (see vv),
      // presumably because the first/last edge are not travelled (in R5, trip starts on a "split")
    }

    "also for a slow car" in {
      val leg = BeamLeg(
        3000,
        BeamMode.CAR,
        0,
        BeamPath(
          Vector(143, 60, 58, 62, 80, 74, 68, 154),
          Vector(),
          None,
          SpaceTime(services.geo.utm2Wgs(origin), 3000),
          SpaceTime(services.geo.utm2Wgs(destination), 3000),
          0.0
        )
      )
      router ! EmbodyWithCurrentTravelTime(
        leg,
        Id.createVehicleId(1),
        Id.create("slowCar", classOf[BeamVehicleType]),
        triggerId = 0
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.head.beamLegs.head.duration == 276)
      // R5 travel time, but less than what's in R5's routing response (see vv),
      // presumably because the first/last edge are not travelled (in R5, trip starts on a "split")
    }

    "also for bikes" in {
      val leg = BeamLeg(
        3000,
        BeamMode.BIKE,
        0,
        BeamPath(
          Vector(143, 60, 58, 62, 80, 74, 68, 154),
          Vector(),
          None,
          SpaceTime(services.geo.utm2Wgs(origin), 3000),
          SpaceTime(services.geo.utm2Wgs(destination), 3000),
          0.0
        )
      )
      router ! EmbodyWithCurrentTravelTime(
        leg,
        Id.createVehicleId(1),
        Id.create("Bicycle", classOf[BeamVehicleType]),
        triggerId = 0
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.head.beamLegs.head.duration == 567)
      // R5 travel time, but less than what's in R5's routing response (see vv),
      // presumably because the first/last edge are not travelled (in R5, trip starts on a "split")
    }

    "take given link traversal times into account" in {
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = false,
        personId = None,
        Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            Id.create("beamVilleCar", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = true,
            needsToCalculateCost = true
          )
        ),
        triggerId = 0
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.exists(_.tripClassifier == CAR))
      val carOption = response.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption.totalTravelTimeInSecs == 147)

      router ! UpdateTravelTimeLocal((_: Link, _: Double, _: Person, _: Vehicle) => 1000) // Every link takes 1000 sec to traverse.
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("car"),
            Id.create("beamVilleCar", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = true,
            needsToCalculateCost = true
          )
        ),
        triggerId = 0
      )
      val response3 = expectMsgType[RoutingResponse]
      assert(response3.itineraries.exists(_.tripClassifier == CAR))
      val carOption3 = response3.itineraries.find(_.tripClassifier == CAR).get
      assert(carOption3.totalTravelTimeInSecs == 3000) // isn't exactly 2000, probably rounding errors?
    }

    "find an equilibrium between my estimation and my experience when I report my self-decided link travel times back to it" in {
      // Start with travel times as calculated by a pristine TravelTimeCalculator.
      // (Should be MATSim free flow travel times)
      val eventsForTravelTimeCalculator = EventsUtils.createEventsManager()
      val travelTimeCalculator =
        new TravelTimeCalculator(beamScenario.network, ConfigUtils.createConfig().travelTimeCalculator())
      eventsForTravelTimeCalculator.addHandler(travelTimeCalculator)
      router ! UpdateTravelTimeLocal(travelTimeCalculator.getLinkTravelTimes)
      val vehicleId = Id.createVehicleId("car")
      router ! RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = false,
        streetVehicles = Vector(
          StreetVehicle(
            vehicleId,
            Id.create("beamVilleCar", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = true,
            needsToCalculateCost = true
          )
        ),
        triggerId = 0
      )
      var carOption = expectMsgType[RoutingResponse].itineraries.find(_.tripClassifier == CAR).get

      // Now feed the TravelTimeCalculator events resulting from me traversing the proposed route,
      // but taking me 2000s (a lot) for each link.
      // Then route again.
      // Like a one-person iterated dynamic traffic assignment.
      def estimatedTotalTravelTime = carOption.totalTravelTimeInSecs
      def longerTravelTimes(enterTime: Int, linkId: Int) = 2000
      def experiencedTotalTravelTime = (carOption.legs(0).beamLeg.travelPath.linkIds.size - 2) * 2000
      // This ^^ is the travel time which I am now reporting to the TravelTimeCalculator, 2000 per fully-traversed link

      def gap = estimatedTotalTravelTime - experiencedTotalTravelTime

      for (_ <- 1 to 10) {
        RoutingModel
          .traverseStreetLeg(carOption.legs(0).beamLeg, vehicleId, longerTravelTimes)
          .foreach(eventsForTravelTimeCalculator.processEvent)

        // Now send the router the travel times resulting from that, and try again.
        router ! UpdateTravelTimeLocal(travelTimeCalculator.getLinkTravelTimes)
        router ! RoutingRequest(
          originUTM = origin,
          destinationUTM = destination,
          departureTime = time,
          withTransit = false,
          streetVehicles = Vector(
            StreetVehicle(
              Id.createVehicleId("car"),
              Id.create("beamVilleCar", classOf[BeamVehicleType]),
              new SpaceTime(new Coord(origin.getX, origin.getY), time),
              Modes.BeamMode.CAR,
              asDriver = true,
              needsToCalculateCost = true
            )
          ),
          triggerId = 0
        )
        carOption = expectMsgType[RoutingResponse].itineraries.find(_.tripClassifier == CAR).getOrElse(carOption)
      }

      assert(scala.math.abs(gap) < 75) // isn't exactly 0, probably rounding errors?
    }

    "give updated travel times for a given route after travel times were updated" in {
      router ! UpdateTravelTimeLocal((_: Link, _: Double, _: Person, _: Vehicle) => 1000) // Every link takes 1000 sec to traverse.
      val leg = BeamLeg(
        28800,
        BeamMode.CAR,
        0,
        BeamPath(
          Vector(1, 2, 3, 4),
          Vector(1, 1, 1, 1),
          None,
          SpaceTime(0.0, 0.0, 28800),
          SpaceTime(1.0, 1.0, 28803),
          1000.0
        )
      )
      router ! EmbodyWithCurrentTravelTime(
        leg,
        Id.createVehicleId(1),
        Id.create("beamVilleCar", classOf[BeamVehicleType]),
        triggerId = 0
      )
      val response = expectMsgType[RoutingResponse]
      assert(response.itineraries.head.beamLegs.head.duration == 3000) // Convention is to traverse from end of first link to end of last, so 3 full links
    }

  }

  override def afterAll(): Unit = {
    shutdown()
    super.afterAll()
  }

}
