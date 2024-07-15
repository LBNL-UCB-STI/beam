package beam.agentsim.events

import java.util
import java.util.concurrent.atomic.AtomicReference
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleEmissions.EmissionsProfile.EmissionsProfile
import beam.router.Modes.BeamMode
import beam.router.model.BeamLeg
import beam.utils.{BeamVehicleUtils, FormatUtils}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

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
  linkIds: IndexedSeq[Int],
  linkTravelTime: IndexedSeq[Double],
  startX: Double,
  startY: Double,
  endX: Double,
  endY: Double,
  primaryFuelConsumed: Double,
  secondaryFuelConsumed: Double,
  endLegPrimaryFuelLevel: Double,
  endLegSecondaryFuelLevel: Double,
  amountPaid: Double,
  fromStopIndex: Option[Int],
  toStopIndex: Option[Int],
  currentTourMode: Option[String],
  riders: IndexedSeq[Id[Person]] = Vector(),
  emissionsProfile: Option[EmissionsProfile]
) extends Event(time)
    with ScalaEvent {
  import PathTraversalEvent._
  import ScalaEvent._

  def capacity: Int = seatingCapacity + standingRoomCapacity

  def linkIdsJava: util.List[Int] = linkIds.asJava

  override def getEventType: String = "PathTraversal"

  private val filledAttrs: AtomicReference[util.Map[String, String]] =
    new AtomicReference[util.Map[String, String]](null)

  override def getAttributes: util.Map[String, String] = {
    if (filledAttrs.get() != null) filledAttrs.get()
    else {
      val attr = super.getAttributes()
      attr.put(ATTRIBUTE_VEHICLE, vehicleId.toString)
      attr.put(ATTRIBUTE_DRIVER, driverId)
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
      attr.put(ATTRIBUTE_PRIMARY_FUEL_LEVEL, primaryFuelConsumed.toString)
      attr.put(ATTRIBUTE_SECONDARY_FUEL_LEVEL, secondaryFuelConsumed.toString)
      attr.put(ATTRIBUTE_VEHICLE_CAPACITY, capacity.toString)

      attr.put(ATTRIBUTE_LOCATION_X, startX.toString)
      attr.put(ATTRIBUTE_LOCATION_Y, startY.toString)
      attr.put(ATTRIBUTE_LOCATION_END_X, endX.toString)
      attr.put(ATTRIBUTE_LOCATION_END_Y, endY.toString)
      attr.put(ATTRIBUTE_PRIMARY_FUEL_LEVEL, endLegPrimaryFuelLevel.toString)
      attr.put(ATTRIBUTE_SECONDARY_FUEL_LEVEL, endLegSecondaryFuelLevel.toString)
      attr.put(ATTRIBUTE_SEATING_CAPACITY, seatingCapacity.toString)
      attr.put(ATTRIBUTE_TOLL_PAID, amountPaid.toString)
      attr.put(ATTRIBUTE_FROM_STOP_INDEX, fromStopIndex.map(_.toString).getOrElse(""))
      attr.put(ATTRIBUTE_TO_STOP_INDEX, toStopIndex.map(_.toString).getOrElse(""))
      attr.put(ATTRIBUTE_CURRENT_TOUR_MODE, currentTourMode.getOrElse(""))
      attr.put(ATTRIBUTE_RIDERS, ridersToStr(riders))
      attr.put(ATTRIBUTE_EMISSIONS_PROFILE, emissionsProfile.map(BeamVehicleUtils.buildEmissionsString).getOrElse(""))
      filledAttrs.set(attr)
      attr
    }
  }
}

object PathTraversalEvent {
  import ScalaEvent._
  val EVENT_TYPE: String = "PathTraversal"

  def apply(
    time: Double,
    vehicleId: Id[Vehicle],
    driverId: String,
    vehicleType: BeamVehicleType,
    numPass: Int,
    beamLeg: BeamLeg,
    currentTourMode: Option[String],
    primaryFuelConsumed: Double,
    secondaryFuelConsumed: Double,
    endLegPrimaryFuelLevel: Double,
    endLegSecondaryFuelLevel: Double,
    amountPaid: Double,
    riders: IndexedSeq[Id[Person]],
    emissionsProfile: Option[EmissionsProfile]
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
      linkTravelTime = beamLeg.travelPath.linkTravelTime,
      startX = beamLeg.travelPath.startPoint.loc.getX,
      startY = beamLeg.travelPath.startPoint.loc.getY,
      endX = beamLeg.travelPath.endPoint.loc.getX,
      endY = beamLeg.travelPath.endPoint.loc.getY,
      primaryFuelConsumed = primaryFuelConsumed,
      secondaryFuelConsumed = secondaryFuelConsumed,
      endLegPrimaryFuelLevel = endLegPrimaryFuelLevel,
      endLegSecondaryFuelLevel = endLegSecondaryFuelLevel,
      amountPaid = amountPaid,
      fromStopIndex = beamLeg.travelPath.transitStops.map(_.fromIdx),
      toStopIndex = beamLeg.travelPath.transitStops.map(_.toIdx),
      currentTourMode = currentTourMode,
      riders = riders,
      emissionsProfile = emissionsProfile
    )
  }

  def apply(genericEvent: Event): PathTraversalEvent = {
    assert(genericEvent.getEventType == EVENT_TYPE)
    val attr = genericEvent.getAttributes.asScala
    val time: Double = genericEvent.getTime
    val capacity: Int = attr(ATTRIBUTE_VEHICLE_CAPACITY).toInt
    val vehicleId: Id[Vehicle] = Id.create(attr(ATTRIBUTE_VEHICLE), classOf[Vehicle])
    val driverId: String = attr(ATTRIBUTE_DRIVER)
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
    val linkIds: IndexedSeq[Int] = if (linkIdsAsStr == "") IndexedSeq.empty else linkIdsAsStr.split(",").map(_.toInt)
    val linkTravelTimeStr = attr.getOrElse(ATTRIBUTE_LINK_TRAVEL_TIME, "")
    val linkTravelTime: IndexedSeq[Double] =
      if (linkTravelTimeStr == null || linkTravelTimeStr == "") IndexedSeq.empty
      else linkTravelTimeStr.split(",").map(_.toDouble)
    val startX: Double = attr(ATTRIBUTE_LOCATION_X).toDouble
    val startY: Double = attr(ATTRIBUTE_LOCATION_Y).toDouble
    val endX: Double = attr(ATTRIBUTE_LOCATION_END_X).toDouble
    val endY: Double = attr(ATTRIBUTE_LOCATION_END_Y).toDouble
    val primaryFuelConsumed: Double = attr(ATTRIBUTE_PRIMARY_FUEL_LEVEL).toDouble
    val secondaryFuelConsumed: Double = attr(ATTRIBUTE_SECONDARY_FUEL_LEVEL).toDouble
    val endLegPrimaryFuelLevel: Double = attr(ATTRIBUTE_PRIMARY_FUEL_LEVEL).toDouble
    val endLegSecondaryFuelLevel: Double = attr(ATTRIBUTE_SECONDARY_FUEL_LEVEL).toDouble
    val amountPaid: Double = attr(ATTRIBUTE_TOLL_PAID).toDouble
    val riders: IndexedSeq[Id[Person]] = ridersFromStr(attr.getOrElse(ATTRIBUTE_RIDERS, ""))
    val fromStopIndex: Option[Int] =
      attr.get(ATTRIBUTE_FROM_STOP_INDEX).flatMap(Option(_)).flatMap(x => if (x == "") None else Some(x.toInt))
    val toStopIndex: Option[Int] =
      attr.get(ATTRIBUTE_TO_STOP_INDEX).flatMap(Option(_)).flatMap(x => if (x == "") None else Some(x.toInt))
    val currentTourMode: Option[String] =
      attr.get(ATTRIBUTE_CURRENT_TOUR_MODE).flatMap(x => if (x == "") None else Some(x))
    val emissionsProfile = attr.get(ATTRIBUTE_EMISSIONS_PROFILE).flatMap(BeamVehicleUtils.parseEmissionsString(_))
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
      currentTourMode,
      riders,
      emissionsProfile
    )
  }

  private def ridersFromStr(ridersStr: String): IndexedSeq[Id[Person]] = {
    if (ridersStr.isEmpty) {
      Vector()
    } else {
      ridersStr.split(":").toIndexedSeq.map(Id.create(_, classOf[Person]))
    }
  }

  private def ridersToStr(riders: IndexedSeq[Id[Person]]): String = {
    riders.mkString(":")
  }
}
