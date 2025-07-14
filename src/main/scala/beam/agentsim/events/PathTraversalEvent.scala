package beam.agentsim.events

import beam.agentsim.agents.freight.PayloadPlan
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile
import beam.router.Modes.BeamMode
import beam.router.model.BeamLeg
import beam.utils.{BeamVehicleUtils, FormatUtils}
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import java.util
import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._

case class PathTraversalEvent(
  time: Double,
  vehicleId: Id[Vehicle],
  driverId: String,
  vehicleType: String,
  seatingCapacity: Int,
  standingRoomCapacity: Int,
  primaryFuelType: String,
  secondaryFuelType: String,
  numberOfPassengers: Int,
  departureTime: Int,
  arrivalTime: Int,
  mode: BeamMode,
  legLength: Double,
  linkIds: Array[Int],
  linkTravelTime: Array[Float],
  startX: Float,
  startY: Float,
  endX: Float,
  endY: Float,
  primaryFuelConsumed: Float,
  secondaryFuelConsumed: Float,
  endLegPrimaryFuelLevel: Float,
  endLegSecondaryFuelLevel: Float,
  amountPaid: Float,
  fromStopIndex: Option[Int],
  toStopIndex: Option[Int],
  currentTripMode: Option[String],
  payloadIds: Array[Id[PayloadPlan]],
  weight: Float,
  emissionsProfile: Option[EmissionsProfile],
  riders: Array[Id[Person]] = Array()
) extends Event(time)
    with ScalaEvent {
  import PathTraversalEvent._

  def capacity: Int = seatingCapacity + standingRoomCapacity

  def linkIdsJava: util.List[Int] = linkIds.toList.asJava

  override def getEventType: String = "PathTraversal"

  override def getAttributes: util.Map[String, String] = {
    val attr = super.getAttributes
    attr.put(ATTRIBUTE_VEHICLE_ID, vehicleId.toString)
    attr.put(ATTRIBUTE_DRIVER_ID, driverId)
    attr.put(ATTRIBUTE_VEHICLE_TYPE, vehicleType)
    attr.put(ATTRIBUTE_LENGTH, legLength.toString)
    attr.put(ATTRIBUTE_NUM_PASS, numberOfPassengers.toString)

    attr.put(ATTRIBUTE_DEPARTURE_TIME, departureTime.toString)
    attr.put(ATTRIBUTE_ARRIVAL_TIME, arrivalTime.toString)
    attr.put(ATTRIBUTE_MODE, mode.value)
    attr.put(ATTRIBUTE_LINK_IDS, linkIds.mkString(","))
    attr.put(ATTRIBUTE_LINK_TRAVEL_TIME, linkTravelTime.map(FormatUtils.DECIMAL_3.format).mkString(","))
    attr.put(ATTRIBUTE_PRIMARY_FUEL_TYPE, primaryFuelType)
    attr.put(ATTRIBUTE_SECONDARY_FUEL_TYPE, secondaryFuelType)
    attr.put(ATTRIBUTE_PRIMARY_FUEL, primaryFuelConsumed.toString)
    attr.put(ATTRIBUTE_SECONDARY_FUEL, secondaryFuelConsumed.toString)
    attr.put(ATTRIBUTE_VEHICLE_CAPACITY, capacity.toString)

    attr.put(ATTRIBUTE_START_COORDINATE_X, startX.toString)
    attr.put(ATTRIBUTE_START_COORDINATE_Y, startY.toString)
    attr.put(ATTRIBUTE_END_COORDINATE_X, endX.toString)
    attr.put(ATTRIBUTE_END_COORDINATE_Y, endY.toString)
    attr.put(ATTRIBUTE_END_LEG_PRIMARY_FUEL_LEVEL, endLegPrimaryFuelLevel.toString)
    attr.put(ATTRIBUTE_END_LEG_SECONDARY_FUEL_LEVEL, endLegSecondaryFuelLevel.toString)
    attr.put(ATTRIBUTE_SEATING_CAPACITY, seatingCapacity.toString)
    attr.put(ATTRIBUTE_TOLL_PAID, amountPaid.toString)
    attr.put(ATTRIBUTE_FROM_STOP_INDEX, fromStopIndex.map(_.toString).getOrElse(""))
    attr.put(ATTRIBUTE_TO_STOP_INDEX, toStopIndex.map(_.toString).getOrElse(""))
    attr.put(ATTRIBUTE_CURRENT_TRIP_MODE, currentTripMode.getOrElse(""))
    attr.put(ATTRIBUTE_PAYLOAD_IDS, payloadIds.mkString(","))
    attr.put(ATTRIBUTE_WEIGHT, weight.toString)
    attr.put(ATTRIBUTE_RIDERS, ridersToStr(riders))
    attr.put(EMISSIONS_PROFILE, emissionsProfile.map(BeamVehicleUtils.buildEmissionsString).getOrElse(""))
    attr
  }
}

object PathTraversalEvent {
  val EVENT_TYPE: String = "PathTraversal"

  val ATTRIBUTE_LENGTH: String = "length"
  val ATTRIBUTE_PRIMARY_FUEL_TYPE: String = "primaryFuelType"
  val ATTRIBUTE_SECONDARY_FUEL_TYPE: String = "secondaryFuelType"
  val ATTRIBUTE_PRIMARY_FUEL: String = "primaryFuel"
  val ATTRIBUTE_SECONDARY_FUEL: String = "secondaryFuel"
  val ATTRIBUTE_NUM_PASS: String = "numPassengers"
  val ATTRIBUTE_CURRENT_TRIP_MODE: String = "currentTripMode"

  val ATTRIBUTE_LINK_IDS: String = "links"
  val ATTRIBUTE_LINK_TRAVEL_TIME: String = "linkTravelTime"
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
  val ATTRIBUTE_END_LEG_PRIMARY_FUEL_LEVEL: String = "primaryFuelLevel"
  val ATTRIBUTE_END_LEG_SECONDARY_FUEL_LEVEL: String = "secondaryFuelLevel"
  val ATTRIBUTE_TOLL_PAID: String = "tollPaid"
  val ATTRIBUTE_SEATING_CAPACITY: String = "seatingCapacity"
  val ATTRIBUTE_FROM_STOP_INDEX: String = "fromStopIndex"
  val ATTRIBUTE_TO_STOP_INDEX: String = "toStopIndex"
  val ATTRIBUTE_PAYLOAD_IDS: String = "payloads"
  val ATTRIBUTE_WEIGHT: String = "weight"
  val EMISSIONS_PROFILE: String = "emissions"
  val ATTRIBUTE_RIDERS: String = "riders"

  def apply(
    time: Double,
    vehicleId: Id[Vehicle],
    driverId: String,
    vehicleType: BeamVehicleType,
    numPass: Int,
    beamLeg: BeamLeg,
    currentTripMode: Option[String],
    primaryFuelConsumed: Float,
    secondaryFuelConsumed: Float,
    endLegPrimaryFuelLevel: Float,
    endLegSecondaryFuelLevel: Float,
    amountPaid: Float,
    payloadIds: Array[Id[PayloadPlan]],
    weight: Float,
    emissionsProfile: Option[EmissionsProfile],
    riders: Array[Id[Person]]
  ): PathTraversalEvent = {
    new PathTraversalEvent(
      time = time,
      vehicleId = vehicleId,
      driverId = driverId,
      vehicleType = vehicleType.id.toString,
      seatingCapacity = vehicleType.seatingCapacity,
      standingRoomCapacity = vehicleType.standingRoomCapacity,
      primaryFuelType = vehicleType.primaryFuelType.toString,
      secondaryFuelType = vehicleType.secondaryFuelType.map(_.toString).getOrElse("None"),
      numberOfPassengers = numPass,
      departureTime = beamLeg.startTime,
      arrivalTime = beamLeg.endTime,
      mode = beamLeg.mode,
      legLength = beamLeg.travelPath.distanceInM,
      linkIds = beamLeg.travelPath.linkIds,
      linkTravelTime = beamLeg.travelPath.linkTravelTime.map(_.toFloat),
      startX = beamLeg.travelPath.startPoint.loc.getX.toFloat,
      startY = beamLeg.travelPath.startPoint.loc.getY.toFloat,
      endX = beamLeg.travelPath.endPoint.loc.getX.toFloat,
      endY = beamLeg.travelPath.endPoint.loc.getY.toFloat,
      primaryFuelConsumed = primaryFuelConsumed,
      secondaryFuelConsumed = secondaryFuelConsumed,
      endLegPrimaryFuelLevel = endLegPrimaryFuelLevel,
      endLegSecondaryFuelLevel = endLegSecondaryFuelLevel,
      amountPaid = amountPaid,
      fromStopIndex = beamLeg.travelPath.transitStops.map(_.fromIdx),
      toStopIndex = beamLeg.travelPath.transitStops.map(_.toIdx),
      currentTripMode = currentTripMode,
      payloadIds = payloadIds,
      weight = weight,
      emissionsProfile = emissionsProfile,
      riders = riders
    )
  }

  def apply(genericEvent: Event): PathTraversalEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala
    val time: Double = genericEvent.getTime
    val capacity: Int = attr(ATTRIBUTE_VEHICLE_CAPACITY).toInt
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE_ID), classOf[Vehicle])
    val driverId: String = attr(ATTRIBUTE_DRIVER_ID)
    val vehicleType: String = attr(ATTRIBUTE_VEHICLE_TYPE)
    val seatingCapacity: Int = attr(ATTRIBUTE_SEATING_CAPACITY).toInt
    val standingRoomCapacity: Int = capacity - seatingCapacity
    val primaryFuelType: String = attr(ATTRIBUTE_PRIMARY_FUEL_TYPE)
    val secondaryFuelType: String = attr(ATTRIBUTE_SECONDARY_FUEL_TYPE)
    val numberOfPassengers: Int = attr(ATTRIBUTE_NUM_PASS).toInt
    val departureTime: Int = attr(ATTRIBUTE_DEPARTURE_TIME).toInt
    val arrivalTime: Int = attr(ATTRIBUTE_ARRIVAL_TIME).toInt
    val mode: BeamMode = BeamMode.fromString(attr(ATTRIBUTE_MODE)).get
    val legLength: Double = attr(ATTRIBUTE_LENGTH).toDouble
    val linkIdsAsStr = Option(attr(ATTRIBUTE_LINK_IDS)).getOrElse("")
    val linkIds: Array[Int] = if (linkIdsAsStr == "") Array.empty else linkIdsAsStr.split(",").map(_.toInt)
    val linkTravelTimeStr = attr.getOrElse(ATTRIBUTE_LINK_TRAVEL_TIME, "")
    val linkTravelTime: Array[Float] =
      if (linkTravelTimeStr == null || linkTravelTimeStr == "") Array.empty
      else linkTravelTimeStr.split(",").map(_.toFloat)
    val startX: Float = attr(ATTRIBUTE_START_COORDINATE_X).toFloat
    val startY: Float = attr(ATTRIBUTE_START_COORDINATE_Y).toFloat
    val endX: Float = attr(ATTRIBUTE_END_COORDINATE_X).toFloat
    val endY: Float = attr(ATTRIBUTE_END_COORDINATE_Y).toFloat
    val primaryFuelConsumed: Float = attr(ATTRIBUTE_PRIMARY_FUEL).toFloat
    val secondaryFuelConsumed: Float = attr(ATTRIBUTE_SECONDARY_FUEL).toFloat
    val endLegPrimaryFuelLevel: Float = attr(ATTRIBUTE_END_LEG_PRIMARY_FUEL_LEVEL).toFloat
    val endLegSecondaryFuelLevel: Float = attr(ATTRIBUTE_END_LEG_SECONDARY_FUEL_LEVEL).toFloat
    val amountPaid: Float = attr(ATTRIBUTE_TOLL_PAID).toFloat
    val payloadIds: Array[Id[PayloadPlan]] = payloadsFromStr(attr.getOrElse(ATTRIBUTE_PAYLOAD_IDS, ""))
    val weight: Float = attr.get(ATTRIBUTE_WEIGHT).fold(0.0f)(_.toFloat)
    val riders: Array[Id[Person]] = ridersFromStr(attr.getOrElse(ATTRIBUTE_RIDERS, ""))
    val fromStopIndex: Option[Int] =
      attr.get(ATTRIBUTE_FROM_STOP_INDEX).flatMap(Option(_)).flatMap(x => if (x == "") None else Some(x.toInt))
    val toStopIndex: Option[Int] =
      attr.get(ATTRIBUTE_TO_STOP_INDEX).flatMap(Option(_)).flatMap(x => if (x == "") None else Some(x.toInt))
    val currentTripMode: Option[String] =
      attr.get(ATTRIBUTE_CURRENT_TRIP_MODE).flatMap(x => if (x == "") None else Some(x))
    val emissionsProfile = attr.get(EMISSIONS_PROFILE).flatMap(BeamVehicleUtils.parseEmissionsString(_))
    PathTraversalEvent(
      time,
      vehicleId,
      driverId,
      vehicleType,
      seatingCapacity,
      standingRoomCapacity,
      primaryFuelType,
      secondaryFuelType,
      numberOfPassengers,
      departureTime,
      arrivalTime,
      mode,
      legLength,
      linkIds,
      linkTravelTime,
      startX,
      startY,
      endX,
      endY,
      primaryFuelConsumed,
      secondaryFuelConsumed,
      endLegPrimaryFuelLevel,
      endLegSecondaryFuelLevel,
      amountPaid,
      fromStopIndex,
      toStopIndex,
      currentTripMode,
      payloadIds,
      weight,
      emissionsProfile,
      riders
    )
  }

  private def ridersFromStr(ridersStr: String): Array[Id[Person]] = {
    if (ridersStr.isEmpty) {
      Array()
    } else {
      ridersStr.split(":").map(Id.create(_, classOf[Person]))
    }
  }

  private def payloadsFromStr(str: String): Array[Id[PayloadPlan]] = {
    if (str.isEmpty) Array.empty
    else str.split(',').map(_.createId[PayloadPlan])
  }

  private def ridersToStr(riders: Array[Id[Person]]): String = {
    riders.mkString(":")
  }
}
