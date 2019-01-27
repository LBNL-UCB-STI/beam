package beam.agentsim.events

import java.util
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.Modes.BeamMode
import beam.router.model.BeamLeg
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle

import collection.JavaConverters._

class PathTraversalEvent(
  val time: Double,
  val vehicleId: Id[Vehicle],
  val driverId: String,
  val vehicleType: String,
  val seatingCapacity: Int,
  val standingRoomCapacity: Int,
  val fuelType: String,
  val numPass: Int,
  val departureTime: Int,
  val arrivalTime: Int,
  val mode: BeamMode,
  val legLength: Double,
  val linkIds: IndexedSeq[Int],
  val linkTravelTimes: IndexedSeq[Int],
  val startX: Double,
  val startY: Double,
  val endX: Double,
  val endY: Double,
  val fuelConsumed: Double,
  val endLegFuelLevel: Double,
  val amountPaid: Double
) extends Event(time) {
  import PathTraversalEvent._

  def capacity: Int = seatingCapacity + standingRoomCapacity

  def linkIdsJava: util.List[Int] = linkIds.asJava

  override def getEventType: String = "PathTraversal"

  private val attributes: AtomicReference[util.Map[String, String]] =
    new AtomicReference[util.Map[String, String]](Collections.emptyMap())

  override def getAttributes: util.Map[String, String] = {
    var attr = attributes.get()
    if (attr == Collections.EMPTY_MAP) {
      attr = super.getAttributes()
      attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
      attr.put(ATTRIBUTE_DRIVER_ID, driverId)
      attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType)
      attr.put(ATTRIBUTE_LENGTH, legLength.toString)
      attr.put(ATTRIBUTE_NUM_PASS, numPass.toString)

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

      attributes.set(attr)
      attr
    } else attr
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
      numPass = numPass,
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
}
