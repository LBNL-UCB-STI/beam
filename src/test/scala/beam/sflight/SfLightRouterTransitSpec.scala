package beam.sflight

import java.io.{BufferedWriter, File, FileWriter}

import akka.actor._
import akka.testkit.TestProbe
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ZonalParkingManagerSpec
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode._
import beam.router.model.EmbodiedBeamTrip
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest._

import scala.concurrent.duration._
import scala.language.postfixOps

class SfLightRouterTransitSpec extends AbstractSfLightSpec("SfLightRouterTransitSpec") with Inside with LazyLogging {
  val boundingBox: Envelope = new Envelope(167000, 833000, 0, 10000000)

  override def beforeAll: Unit = {
    super.beforeAll
    val zonalParkingManager = ZonalParkingManagerSpec.mockZonalParkingManager(services, boundingBox)
    within(5 minutes) { // Router can take a while to initialize
      router ! InitTransit(new TestProbe(system).ref, zonalParkingManager)
      expectMsgType[Any] // success
    }
  }

  "A router" must {
    "respond with a route to a first reasonable RoutingRequest" in {
      val origin = geoUtil.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val destination = geoUtil.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val time = 25740
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
      val transitOption = response.itineraries.find(_.tripClassifier == WALK_TRANSIT).get
      assertMakesSense(transitOption)
      assert(transitOption.costEstimate == 2.75)
      assert(transitOption.legs.head.beamLeg.startTime == 25992)
    }

    "respond with a drive_transit and a walk_transit route for each trip in sflight" ignore {
      scenario.getPopulation.getPersons
        .values()
        .forEach(person => {
          val activities = planToVec(person.getSelectedPlan)
          activities
            .sliding(2)
            .foreach(pair => {
              val origin = pair(0).getCoord
              val destination = pair(1).getCoord
              val time = pair(0).getEndTime.toInt
              router ! RoutingRequest(
                origin,
                destination,
                time,
                withTransit = true,
                Vector(
                  StreetVehicle(
                    Id.createVehicleId("116378-2"),
                    BeamVehicleType.defaultCarBeamVehicleType.id,
                    new SpaceTime(origin, 0),
                    CAR,
                    asDriver = true
                  ),
                  StreetVehicle(
                    Id.createVehicleId("body-116378-2"),
                    BeamVehicleType.defaultCarBeamVehicleType.id,
                    new SpaceTime(new Coord(origin.getX, origin.getY), time),
                    WALK,
                    asDriver = true
                  )
                )
              )
              val response = expectMsgType[RoutingResponse]

              // writeResponseToFile(origin, destination, time, response)
              if (!response.itineraries.exists(_.tripClassifier == DRIVE_TRANSIT)) {
                logger.debug("failure here")
              }

              assert(response.itineraries.exists(_.costEstimate > 0))
              assert(response.itineraries.filter(_.tripClassifier.isTransit).forall(_.costEstimate > 0))
              assert(response.itineraries.exists(_.tripClassifier == DRIVE_TRANSIT))
              assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
            })
        })
    }

    "respond with a unlimited transfer route having cost 2.75 USD." in {
      val origin = new Coord(549598.9574660371, 4176177.2431860007)
      val destination = new Coord(544417.3891361314, 4177016.733758491)
      val time = 64080
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.costEstimate == 2.75))
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
    }

    "respond with a BART route without transfer having cost 1.95 USD." in {
      val origin = geoUtil.wgs2Utm(new Coord(-122.41969, 37.76506)) // 16th St. Mission
      val destination = geoUtil.wgs2Utm(new Coord(-122.40686, 37.784992)) // Powell St.
      val time = 51840
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            BeamVehicleType.defaultCarBeamVehicleType.id,
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        )
      )
      val response = expectMsgType[RoutingResponse]

      assert(response.itineraries.exists(_.costEstimate == 1.95))
      assert(response.itineraries.exists(_.tripClassifier == WALK))
      assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
    }

  }

//  Vector(itinerary ->, [x=550046.6183707184][y=4173684.1312090624], [x=551010.1423040839][y=4184361.3484820053], DiscreteTime(54960), WALK_TRANSIT, 18.70

  private def printResponse(
    origin: Location,
    destination: Location,
    time: Int,
    response: RoutingResponse
  ): Unit = {
    response.itineraries.foreach(
      it =>
        logger.debug(
          Vector(
            "itinerary ->",
            origin,
            destination,
            time,
            it.tripClassifier,
            it.costEstimate,
            it.legs.zipWithIndex.map(
              t =>
                (
                  t._1.beamLeg.mode,
                  it.legs.zipWithIndex.filter(_._2 < t._2).map(_._1.beamLeg.duration).sum
              )
            )
          ).toString()
      )
    )
  }

  private def writeResponseToFile(
    personId: Id[Person],
    origin: Location,
    destination: Location,
    time: Int,
    response: RoutingResponse
  ): Unit = {
    val writer = new BufferedWriter(new FileWriter(new File("d:/test-out.txt"), true))
    response.itineraries.foreach(
      it =>
        writer.append(
          Vector(
            "itinerary ->",
            personId.toString,
            origin,
            destination,
            time,
            it.tripClassifier,
            it.costEstimate,
            it.legs.zipWithIndex.map(
              t =>
                (
                  t._1.beamLeg.mode,
                  it.legs.zipWithIndex.filter(_._2 < t._2).map(_._1.beamLeg.duration).sum
              )
            )
          ).toString() + "\n"
      )
    )
    writer.close()
  }

  def assertMakesSense(trip: EmbodiedBeamTrip): Unit = {
    var time = trip.legs.head.beamLeg.startTime
    trip.legs.foreach(leg => {
      assert(leg.beamLeg.startTime >= time, "Leg starts when or after previous one finishes.")
      time += leg.beamLeg.duration
    })
  }
}
