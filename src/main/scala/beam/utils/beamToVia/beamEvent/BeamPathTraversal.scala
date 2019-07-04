package beam.utils.beamToVia.beamEvent

import beam.router.Modes.BeamMode
import beam.utils.beamToVia.viaEvent.{EnteredLink, LeftLink, ViaEvent, ViaTraverseLinkEvent}
import org.matsim.api.core.v01.events.Event

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.xml.Elem

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
    numberOfPassengers: Int,
    arrivalTime: Int,
    linkIds: IndexedSeq[Int],
    linkTravelTime: IndexedSeq[Int]
  ): BeamPathTraversal =
    new BeamPathTraversal(
      time,
      vehicleId,
      driverId,
      vehicleType,
      mode,
      numberOfPassengers,
      arrivalTime,
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
    val arrivalTime: Int = attr(ATTRIBUTE_ARRIVAL_TIME).toInt

    val linkIdsAsStr = Option(attr.getOrElse(ATTRIBUTE_LINK_IDS, ""))
    val linkIds: IndexedSeq[Int] = linkIdsAsStr match {
      case None | Some("") => IndexedSeq.empty
      case Some(v)         => v.split(",").map(_.toInt)
    }

    val linkTravelTimeStr = Option(attr.getOrElse(ATTRIBUTE_LINK_TRAVEL_TIME, ""))
    val linkTravelTime: IndexedSeq[Int] = linkTravelTimeStr match {
      case None | Some("") =>
        if (linkIds.nonEmpty) {
          val travelTime = arrivalTime - time
          val links = linkIds.size
          val averageValue = travelTime / links

          val beginning = IndexedSeq.fill[Int](links - 1)(averageValue)
          beginning :+ (travelTime - averageValue * (links - 1))
        } else IndexedSeq.empty
      case Some(v) => v.split(",").map(_.toInt)
    }

    val mode: BeamMode = BeamMode.fromString(attr(ATTRIBUTE_MODE)).get

    BeamPathTraversal(
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
  linkIds: IndexedSeq[Int],
  linkTravelTime: IndexedSeq[Int]
) extends BeamEvent {

  def toViaEvents(vehicleId: String, timeLimit: Option[Double]): Seq[ViaEvent] = {
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
          val entered = ViaTraverseLinkEvent(enteredTime, vehicleId, EnteredLink, linkId)
          val left = ViaTraverseLinkEvent(leftTime, vehicleId, LeftLink, linkId)
          Seq(entered, left)
      }

    paths
  }

  override def toXml: Elem =
    <event time={time.toString} type="PathTraversal" vehicle={vehicleId} driver={driverId} vehicleType={vehicleType} 
           numPassangers={numberOfPassengers.toString} departureTime={time.toString} arrivalTime={arrivalTime.toString} 
           mode={mode} links={linkIds.mkString(",")} linkTravelTime={linkTravelTime.mkString(",")}/>
}
