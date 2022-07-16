package scripts.beam_to_matsim.events

import beam.router.Modes.BeamMode
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Coord, Id}
import scripts.beam_to_matsim.transit.TransitHelper
import scripts.beam_to_matsim.via_event.ViaTraverseLinkEvent

import scala.collection.JavaConverters._
import scala.collection.mutable

sealed trait BeamPathTraversal extends BeamEvent {
  def time: Double
  def vehicleId: String
  def driverId: String
  def vehicleType: String
  def mode: String
  def numberOfPassengers: Int
  def arrivalTime: Int
}

case class PathTraversalWithLinks(
  time: Double,
  vehicleId: String,
  driverId: String,
  vehicleType: String,
  mode: String,
  numberOfPassengers: Int,
  arrivalTime: Int,
  var linkIds: Seq[Int],
  var linkTravelTime: Seq[Double]
) extends BeamPathTraversal {
  assert(linkIds.size == linkTravelTime.size, "linkIds size does not equal to linkTravelTime size")

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  def removeHeadLinkFromTrip(): Unit = {
    linkIds = linkIds.tail
    linkTravelTime = linkTravelTime.tail
  }

  def adjustTime(deltaTime: Double): Unit = {
    val travelTime = arrivalTime - time
    val delta = (travelTime + deltaTime) / travelTime
    linkTravelTime = linkTravelTime.map(x => x * delta)
  }

  def toViaEvents(
    vehicleId: String,
    timeLimit: Option[Double]
  ): Seq[ViaTraverseLinkEvent] = {
    val onePiece: Double = timeLimit match {
      case None => 1.0
      case Some(limit) =>
        val sum: Double = linkTravelTime.sum * 1.0
        if (time + sum <= limit) 1.0
        else (limit - time) / sum
    }

    val (_, times) =
      linkTravelTime.foldLeft((time, mutable.MutableList.empty[(Double, Double)])) {
        case ((lastTime, timeList), travelTime) =>
          val linkTime = lastTime + Math.round(travelTime * onePiece)
          timeList += Tuple2(lastTime, linkTime)
          (linkTime, timeList)
      }

    val paths = linkIds
      .zip(times)
      .flatMap { case (linkId, (enteredTime, leftTime)) =>
        val entered = ViaTraverseLinkEvent.entered(enteredTime, vehicleId, linkId)
        val left = ViaTraverseLinkEvent.left(leftTime, vehicleId, linkId)
        Seq(entered, left)
      }

    paths
  }
}

case class PathTraversalWithoutLinks(
  time: Double,
  vehicleId: String,
  driverId: String,
  vehicleType: String,
  mode: String,
  numberOfPassengers: Int,
  arrivalTime: Int,
  startCoord: Coord,
  endCoord: Coord
) extends BeamPathTraversal {

  def toViaEvents(
    vehicleId: String,
    linkId: Id[Link]
  ): Seq[ViaTraverseLinkEvent] = {
    val entered = ViaTraverseLinkEvent.entered(time, vehicleId, linkId.toString.toInt)
    val left = ViaTraverseLinkEvent.left(arrivalTime, vehicleId, linkId.toString.toInt)
    Seq(entered, left)
  }
}

object BeamPathTraversal {
  val EVENT_TYPE: String = "PathTraversal"
  val ATTRIBUTE_NUM_PASS: String = "numPassengers"
  val ATTRIBUTE_MODE: String = "mode"
  val ATTRIBUTE_LINK_IDS: String = "links"
  val ATTRIBUTE_LINK_TRAVEL_TIME: String = "linkTravelTime"
  val ATTRIBUTE_DEPARTURE_TIME: String = "departureTime"
  val ATTRIBUTE_ARRIVAL_TIME: String = "arrivalTime"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_DRIVER_ID: String = "driver"
  val ATTRIBUTE_VEHICLE_TYPE: String = "vehicleType"
  val ATTRIBUTE_START_X: String = "startX"
  val ATTRIBUTE_START_Y: String = "startY"
  val ATTRIBUTE_END_X: String = "endX"
  val ATTRIBUTE_END_Y: String = "endY"

  def isLinksAvailable(genericEvent: Event): Boolean = {
    val linkIdsAsStr = Option(genericEvent.getAttributes.asScala.getOrElse(ATTRIBUTE_LINK_IDS, ""))
    linkIdsAsStr match {
      case None | Some("") => false
      case Some(v)         => true
    }
  }

  def withLinks(genericEvent: Event): BeamPathTraversal = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val vehicleId: String = attr(ATTRIBUTE_VEHICLE_ID)
    val driverId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleType: String = attr(ATTRIBUTE_VEHICLE_TYPE)
    val numberOfPassengers: Int = attr(ATTRIBUTE_NUM_PASS).toDouble.toInt
    val time: Int = attr(ATTRIBUTE_DEPARTURE_TIME).toDouble.toInt
    var arrivalTime: Int = attr(ATTRIBUTE_ARRIVAL_TIME).toDouble.toInt

    val linkIdsAsStr = Option(attr.getOrElse(ATTRIBUTE_LINK_IDS, ""))
    val linkIds: Seq[Int] = linkIdsAsStr match {
      case None | Some("") => Seq.empty
      case Some(v)         => v.split(",").map(_.toInt)
    }

    val linkTravelTimeStr = Option(attr.getOrElse(ATTRIBUTE_LINK_TRAVEL_TIME, ""))
    val linkTravelTime: Seq[Double] = linkTravelTimeStr match {
      case None | Some("") =>
        if (linkIds.nonEmpty) {
          val travelTime = arrivalTime - time
          val links = linkIds.size
          val averageValue = travelTime / links

          val beginning = IndexedSeq.fill[Double](links - 1)(averageValue)
          val tail = travelTime - averageValue * (links - 1.0)
          beginning :+ tail
        } else
          Seq.empty

      case Some(v) =>
        val travelTimes = v.split(",").map(_.toDouble)
        arrivalTime = (time + travelTimes.sum).toInt
        travelTimes
    }

    val mode: BeamMode = BeamMode.fromString(attr(ATTRIBUTE_MODE)).get

    PathTraversalWithLinks(
      time,
      vehicleId,
      driverId,
      vehicleType,
      mode.toString,
      numberOfPassengers,
      arrivalTime,
      linkIds,
      linkTravelTime
    )
  }

  def withoutLinks(genericEvent: Event): BeamPathTraversal = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val vehicleId: String = attr(ATTRIBUTE_VEHICLE_ID)
    val driverId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleType: String = attr(ATTRIBUTE_VEHICLE_TYPE)
    val numberOfPassengers: Int = attr(ATTRIBUTE_NUM_PASS).toDouble.toInt
    val time: Int = attr(ATTRIBUTE_DEPARTURE_TIME).toDouble.toInt
    val arrivalTime: Int = attr(ATTRIBUTE_ARRIVAL_TIME).toDouble.toInt
    val mode: BeamMode = BeamMode.fromString(attr(ATTRIBUTE_MODE)).get

    val startX: Double = TransitHelper.round(attr(ATTRIBUTE_START_X).toDouble)
    val startY: Double = TransitHelper.round(attr(ATTRIBUTE_START_Y).toDouble)
    val endX: Double = TransitHelper.round(attr(ATTRIBUTE_END_X).toDouble)
    val endY: Double = TransitHelper.round(attr(ATTRIBUTE_END_Y).toDouble)

    PathTraversalWithoutLinks(
      time,
      vehicleId,
      driverId,
      vehicleType,
      mode.toString,
      numberOfPassengers,
      arrivalTime,
      new Coord(startX, startY),
      new Coord(endX, endY)
    )
  }
}
