package beam.utils.beam_to_matsim.events

import beam.router.Modes.BeamMode
import beam.utils.beam_to_matsim.via_event.ViaTraverseLinkEvent
import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable

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

  def apply(
    time: Double,
    vehicleId: String,
    driverId: String,
    vehicleType: String,
    mode: String,
    linkIds: Seq[Int],
    linkTravelTime: Seq[Double],
    numberOfPassengers: Int = 0
  ): BeamPathTraversal =
    new BeamPathTraversal(
      time,
      vehicleId,
      driverId,
      vehicleType,
      mode,
      numberOfPassengers,
      linkTravelTime.sum.toInt,
      linkIds,
      linkTravelTime
    )

  def apply(genericEvent: Event): BeamPathTraversal = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr: mutable.Map[String, String] = genericEvent.getAttributes.asScala

    val vehicleId: String = attr(ATTRIBUTE_VEHICLE_ID)
    val driverId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleType: String = attr(ATTRIBUTE_VEHICLE_TYPE)
    val numberOfPassengers: Int = attr(ATTRIBUTE_NUM_PASS).toInt
    val time: Int = attr(ATTRIBUTE_DEPARTURE_TIME).toInt
    var arrivalTime: Int = attr(ATTRIBUTE_ARRIVAL_TIME).toInt

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

    new BeamPathTraversal(
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
}

case class BeamPathTraversal(
  time: Double,
  vehicleId: String,
  driverId: String,
  vehicleType: String,
  mode: String,
  numberOfPassengers: Int,
  arrivalTime: Int,
  var linkIds: Seq[Int],
  var linkTravelTime: Seq[Double]
) extends BeamEvent {

  assert(linkIds.size == linkTravelTime.size, "linkIds size does not equal to linkTravelTime size")

  def removeHeadLinkFromTrip(): Unit = {
    linkIds = linkIds.tail
    linkTravelTime = linkTravelTime.tail
  }

  def adjustTime(deltaTime: Double): Unit = {
    val travelTime = arrivalTime - time
    val delta = (travelTime + deltaTime) / travelTime
    linkTravelTime = linkTravelTime.map(x => x * delta)
  }

  def toViaEvents(vehicleId: String, timeLimit: Option[Double]): Seq[ViaTraverseLinkEvent] = {
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
      .flatMap {
        case (linkId, (enteredTime, leftTime)) =>
          val entered = ViaTraverseLinkEvent.entered(enteredTime, vehicleId, linkId)
          val left = ViaTraverseLinkEvent.left(leftTime, vehicleId, linkId)
          Seq(entered, left)
      }

    paths
  }
}
