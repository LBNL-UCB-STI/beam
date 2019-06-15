package beam.sflight

import akka.actor._
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter._
import beam.router.Modes.BeamMode._
import beam.router.model.EmbodiedBeamTrip
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest._

import scala.language.postfixOps

class SfLightRouterTransitSpec extends AbstractSfLightSpec("SfLightRouterTransitSpec") with Inside with LazyLogging {

  "A router" must {
    "respond with a route to a first reasonable RoutingRequest" in {
      val origin = services.geo.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val destination = services.geo.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val time = 25740
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
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

    "respond with a drive_transit and a walk_transit route for each trip in sflight" in {
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
                    Id.create("Car", classOf[BeamVehicleType]),
                    new SpaceTime(origin, 0),
                    CAR,
                    asDriver = true
                  ),
                  StreetVehicle(
                    Id.createVehicleId("body-116378-2"),
                    Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
                    new SpaceTime(new Coord(origin.getX, origin.getY), time),
                    WALK,
                    asDriver = true
                  )
                )
              )
              val response = expectMsgType[RoutingResponse]
              assert(response.itineraries.exists(_.tripClassifier == DRIVE_TRANSIT))
              assert(response.itineraries.exists(_.tripClassifier == WALK_TRANSIT))
              assert(response.itineraries.filter(_.tripClassifier.isTransit).forall(_.costEstimate > 0))
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
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
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
      val origin = services.geo.wgs2Utm(new Coord(-122.41969, 37.76506)) // 16th St. Mission
      val destination = services.geo.wgs2Utm(new Coord(-122.40686, 37.784992)) // Powell St.
      val time = 51840
      router ! RoutingRequest(
        origin,
        destination,
        time,
        withTransit = true,
        Vector(
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
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

  def assertMakesSense(trip: EmbodiedBeamTrip): Unit = {
    var time = trip.legs.head.beamLeg.startTime
    trip.legs.foreach(leg => {
      assert(leg.beamLeg.startTime >= time, "Leg starts when or after previous one finishes.")
      time += leg.beamLeg.duration
    })
  }
}
