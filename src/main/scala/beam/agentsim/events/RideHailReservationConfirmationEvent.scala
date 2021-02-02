package beam.agentsim.events

import java.util

import beam.agentsim.events.RideHailReservationConfirmationEvent.RideHailReservationType
import beam.agentsim.events.resources.ReservationErrorCode
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}

object RideHailReservationConfirmationEvent {
  val EVENT_TYPE: String = "RideHailReservationConfirmation"
  val ATTRIBUTE_PERSON = "person"
  val ATTRIBUTE_RESERVATION_TYPE: String = "reservationType"
  val ATTRIBUTE_RESERVATION_ERROR_CODE: String = "errorCode"
  val ATTRIBUTE_RESERVATION_TIME: String = "reservationTime"
  val ATTRIBUTE_REQUESTED_PICKUP_TIME: String = "requestedPickupTime"
  val ATTRIBUTE_QUOTED_WAIT_TIME: String = "quotedWaitTimeInS"
  val ATTRIBUTE_PICKUP_LOCATION_X: String = "startX"
  val ATTRIBUTE_PICKUP_LOCATION_Y: String = "startY"
  val ATTRIBUTE_DROPOFF_LOCATION_X: String = "endX"
  val ATTRIBUTE_DROPOFF_LOCATION_Y: String = "endY"
  val ATTRIBUTE_OFFERED_PICKUP_TIME: String = "offeredPickupTime"
  val ATTRIBUTE_DIRECT_ROUTE_DISTANCE: String = "directRouteDistanceInM"
  val ATTRIBUTE_DIRECT_ROUTE_TIME: String = "directRouteDurationInS"

  def typeWhenPooledIs(isPooled: Boolean): RideHailReservationType = {
    if (isPooled) {
      Pooled
    } else {
      Solo
    }
  }
  sealed trait RideHailReservationType
  case object Solo extends RideHailReservationType
  case object Pooled extends RideHailReservationType
}

/**
  * Event capturing the details of a ride hail reservation confirmation
  */
class RideHailReservationConfirmationEvent(
  val time: Double,
  val personId: Id[Person],
  val reservationType: RideHailReservationType,
  val reservationErrorCodeOpt: Option[ReservationErrorCode],
  val reservationTime: Int,
  /* This represents the time when the reservation was made, not when it was confirmed. They are usually the same */
  val requestedPickUpTime: Int,
  /*  In BEAM this currently is always same as the reservationTime, but in future we could implement the ability to reserve a ride well ahead of time */
  val quotedWaitTimeOpt: Option[Int],
  /*  Most (but not all) reservations begin with a price/wait quote, this records this */
  val pickUpLocationWgs: Coord, /* Same CRS as in PathTraversalEvent */
  val dropOffLocationWgs: Coord, /* Same CRS as in PathTraversalEvent */
  val offeredPickUpTimeOpt: Option[Int], /*  None if the reservation failed */
  val directRouteDistanceInMOpt: Option[Double],
  val directRouteDurationInSOpt: Option[Int],
) extends Event(time)
    with ScalaEvent {
  import RideHailReservationConfirmationEvent._

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_RESERVATION_TYPE, reservationType.toString)
    attributes.put(ATTRIBUTE_PERSON, personId.toString)
    attributes.put(ATTRIBUTE_RESERVATION_ERROR_CODE, reservationErrorCodeOpt.map(_.toString).getOrElse(""))
    attributes.put(ATTRIBUTE_RESERVATION_TIME, reservationTime.toString)
    attributes.put(ATTRIBUTE_REQUESTED_PICKUP_TIME, requestedPickUpTime.toString)
    attributes.put(ATTRIBUTE_QUOTED_WAIT_TIME, quotedWaitTimeOpt.map(_.toString).getOrElse(""))
    attributes.put(ATTRIBUTE_PICKUP_LOCATION_X, pickUpLocationWgs.getX.toString)
    attributes.put(ATTRIBUTE_PICKUP_LOCATION_Y, pickUpLocationWgs.getY.toString)
    attributes.put(ATTRIBUTE_DROPOFF_LOCATION_X, dropOffLocationWgs.getX.toString)
    attributes.put(ATTRIBUTE_DROPOFF_LOCATION_Y, dropOffLocationWgs.getY.toString)
    attributes.put(ATTRIBUTE_OFFERED_PICKUP_TIME, offeredPickUpTimeOpt.map(_.toString).getOrElse(""))
    attributes.put(ATTRIBUTE_DIRECT_ROUTE_DISTANCE, directRouteDistanceInMOpt.map(_.toString).getOrElse(""))
    attributes.put(ATTRIBUTE_DIRECT_ROUTE_TIME, directRouteDurationInSOpt.map(_.toString).getOrElse(""))
    attributes
  }
}
