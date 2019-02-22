package beam.agentsim.events

import java.util

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.Modes.BeamMode
import beam.router.model.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, GenericEvent}
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._

case class PathTraversalEvent(
  time: Double,
  vehicleId: Id[Vehicle],
  driverId: String,
  vehicleType: String,
  seatingCapacity: Int,
  standingRoomCapacity: Int,
  fuelType: String,
  numberOfPassengers: Int,
  departureTime: Int,
  arrivalTime: Int,
  mode: BeamMode,
  legLength: Double,
  linkIds: IndexedSeq[Int],
  linkTravelTimes: IndexedSeq[Int],
  startX: Double,
  startY: Double,
  endX: Double,
  endY: Double,
  fuelConsumed: Double,
  endLegFuelLevel: Double,
  amountPaid: Double
) extends Event(time)
    with ScalaEvent {
  import PathTraversalEvent._

  def capacity: Int = seatingCapacity + standingRoomCapacity

  def linkIdsJava: util.List[Int] = linkIds.asJava

  override def getEventType: String = "PathTraversal"

  override def getAttributes: util.Map[String, String] = {
    val attr = super.getAttributes()
    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_DRIVER_ID, driverId)
    attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType)
    attr.put(ATTRIBUTE_LENGTH, legLength.toString)
    attr.put(ATTRIBUTE_NUM_PASS, numberOfPassengers.toString)

    attr.put(ATTRIBUTE_DEPARTURE_TIME, departureTime.toString)
    attr.put(ATTRIBUTE_ARRIVAL_TIME, arrivalTime.toString)
    attr.put(ATTRIBUTE_MODE, mode.value)
    attr.put(ATTRIBUTE_LINK_IDS, linkIds.mkString(","))
    attr.put(ATTRIBUTE_FUEL_TYPE, fuelType)
    attr.put(ATTRIBUTE_FUEL, fuelConsumed.toString)
    attr.put(ATTRIBUTE_VEHICLE_CAPACITY, capacity.toString)

    attr.put(ATTRIBUTE_START_COORDINATE_X, startX.toString)
    attr.put(ATTRIBUTE_START_COORDINATE_Y, startY.toString)
    attr.put(ATTRIBUTE_END_COORDINATE_X, endX.toString)
    attr.put(ATTRIBUTE_END_COORDINATE_Y, endY.toString)
    attr.put(ATTRIBUTE_END_LEG_FUEL_LEVEL, endLegFuelLevel.toString)
    attr.put(ATTRIBUTE_SEATING_CAPACITY, seatingCapacity.toString)
    attr.put(ATTRIBUTE_TOLL_PAID, amountPaid.toString)
    attr
  }
}

object PathTraversalEvent {
  val EVENT_TYPE: String = "PathTraversal"

  val ATTRIBUTE_LENGTH: String = "length"
  val ATTRIBUTE_FUEL_TYPE: String = "fuelType"
  val ATTRIBUTE_FUEL: String = "fuel"
  val ATTRIBUTE_NUM_PASS: String = "numPassengers"

  val ATTRIBUTE_LINK_IDS: String = "links"
  val ATTRIBUTE_MODE: String = "mode"
  val ATTRIBUTE_DEPARTURE_TIME: String = "departureTime"
  val ATTRIBUTE_ARRIVAL_TIME: String = "arrivalTime"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_DRIVER_ID: String = "driver"
  val ATTRIBUTE_VEHICLE_TYPE: String = "vehicleType"
  val ATTRIBUTE_VEHICLE_CAPACITY: String = "capacity"
  val ATTRIBUTE_START_COORDINATE_X: String = "startX"
  val ATTRIBUTE_START_COORDINATE_Y: String = "startY"
  val ATTRIBUTE_END_COORDINATE_X: String = "endX"
  val ATTRIBUTE_END_COORDINATE_Y: String = "endY"
  val ATTRIBUTE_END_LEG_FUEL_LEVEL: String = "endLegFuelLevel"
  val ATTRIBUTE_TOLL_PAID: String = "tollPaid"
  val ATTRIBUTE_SEATING_CAPACITY: String = "seatingCapacity"

  def apply(
    time: Double,
    vehicleId: Id[Vehicle],
    driverId: String,
    vehicleType: BeamVehicleType,
    numPass: Int,
    beamLeg: BeamLeg,
    fuelConsumed: Double,
    endLegFuelLevel: Double,
    amountPaid: Double
  ): PathTraversalEvent = {
    new PathTraversalEvent(
      time = time,
      vehicleId = vehicleId,
      driverId = driverId,
      vehicleType = vehicleType.id.toString,
      seatingCapacity = vehicleType.seatingCapacity,
      standingRoomCapacity = vehicleType.standingRoomCapacity,
      fuelType = vehicleType.primaryFuelType.toString,
      numberOfPassengers = numPass,
      departureTime = beamLeg.startTime,
      arrivalTime = beamLeg.endTime,
      mode = beamLeg.mode,
      legLength = beamLeg.travelPath.distanceInM,
      linkIds = beamLeg.travelPath.linkIds,
      linkTravelTimes = beamLeg.travelPath.linkTravelTime,
      startX = beamLeg.travelPath.startPoint.loc.getX,
      startY = beamLeg.travelPath.startPoint.loc.getY,
      endX = beamLeg.travelPath.endPoint.loc.getX,
      endY = beamLeg.travelPath.endPoint.loc.getY,
      fuelConsumed = fuelConsumed,
      endLegFuelLevel = endLegFuelLevel,
      amountPaid = amountPaid
    )
  }

  def apply(genericEvent: GenericEvent): PathTraversalEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala
    val time: Double = genericEvent.getTime
    val capacity: Int = attr(ATTRIBUTE_VEHICLE_CAPACITY).toInt
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE_ID), classOf[Vehicle])
    val driverId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleType: String = attr(ATTRIBUTE_VEHICLE_TYPE)
    val seatingCapacity: Int = attr(ATTRIBUTE_SEATING_CAPACITY).toInt
    val standingRoomCapacity: Int = capacity - seatingCapacity
    val fuelType: String = attr(ATTRIBUTE_FUEL_TYPE)
    val numberOfPassengers: Int = attr(ATTRIBUTE_NUM_PASS).toInt
    val departureTime: Int = attr(ATTRIBUTE_DEPARTURE_TIME).toInt
    val arrivalTime: Int = attr(ATTRIBUTE_ARRIVAL_TIME).toInt
    val mode: BeamMode = BeamMode.fromString(attr(ATTRIBUTE_MODE)).get
    val legLength: Double = attr(ATTRIBUTE_LENGTH).toDouble
    val linkIdsAsStr = attr(ATTRIBUTE_LINK_IDS)
    val linkIds: IndexedSeq[Int] = if (linkIdsAsStr == "") IndexedSeq.empty else linkIdsAsStr.split(",").map(_.toInt)
    // TODO. We don't dump link travel time, shall we ?
    val linkTravelTimes: IndexedSeq[Int] = IndexedSeq.empty
    val startX: Double = attr(ATTRIBUTE_START_COORDINATE_X).toDouble
    val startY: Double = attr(ATTRIBUTE_START_COORDINATE_Y).toDouble
    val endX: Double = attr(ATTRIBUTE_END_COORDINATE_X).toDouble
    val endY: Double = attr(ATTRIBUTE_END_COORDINATE_Y).toDouble
    val fuelConsumed: Double = attr(ATTRIBUTE_FUEL).toDouble
    val endLegFuelLevel: Double = attr(ATTRIBUTE_END_LEG_FUEL_LEVEL).toDouble
    val amountPaid: Double = attr(ATTRIBUTE_TOLL_PAID).toDouble
    PathTraversalEvent(
      time,
      vehicleId,
      driverId,
      vehicleType,
      seatingCapacity,
      standingRoomCapacity,
      fuelType,
      numberOfPassengers,
      departureTime,
      arrivalTime,
      mode,
      legLength,
      linkIds,
      linkTravelTimes,
      startX,
      startY,
      endX,
      endY,
      fuelConsumed,
      endLegFuelLevel,
      amountPaid
    )
  }
}
