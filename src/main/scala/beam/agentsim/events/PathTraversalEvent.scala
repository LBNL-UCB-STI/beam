package beam.agentsim.events

import java.util
import java.util.concurrent.atomic.AtomicReference

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.Modes.BeamMode
import beam.router.model.BeamLeg
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
  toStopIndex: Option[Int], /*,
  linkIdsToLaneOptions: IndexedSeq[(Int, Option[Int])],
  linkIdsToSpeedOptions: IndexedSeq[(Int, Option[Double])],
  linkIdsToGradientOptions: IndexedSeq[(Int, Option[Double])],
  linkIdsToLengthOptions: IndexedSeq[(Int, Option[Double])],
  linkIdsToSelectedRateOptions: IndexedSeq[(Int, Option[Double])],
  linkIdsToConsumptionOptions: IndexedSeq[(Int, Option[Double])],
  secondaryLinkIdsToSelectedRateOptions: IndexedSeq[(Int, Option[Double])],
  secondaryLinkIdsToConsumptionOptions: IndexedSeq[(Int, Option[Double])]*/
  riders: IndexedSeq[Id[Person]] = Vector()
) extends Event(time)
    with ScalaEvent {
  import PathTraversalEvent._

  def capacity: Int = seatingCapacity + standingRoomCapacity

  def linkIdsJava: util.List[Int] = linkIds.asJava

  override def getEventType: String = "PathTraversal"

  private val filledAttrs: AtomicReference[util.Map[String, String]] =
    new AtomicReference[util.Map[String, String]](null)

  override def getAttributes: util.Map[String, String] = {
    if (filledAttrs.get() != null) filledAttrs.get()
    else {
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
      attr.put(ATTRIBUTE_LINK_TRAVEL_TIME, linkTravelTime.mkString(","))
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
      /*
    attr.put(ATTRIBUTE_LINKID_WITH_LANE_MAP, linkIdsToLaneOptions.map{case ((linkId, laneOption)) => s"$linkId:${laneOption.getOrElse(0)}"}.mkString(","))
    attr.put(ATTRIBUTE_LINKID_WITH_SPEED_MAP, linkIdsToSpeedOptions.map{case ((linkId, speedOption)) => s"$linkId:${speedOption.getOrElse(0)}"}.mkString(","))
    attr.put(ATTRIBUTE_LINKID_WITH_SELECTED_GRADIENT_MAP, linkIdsToGradientOptions.map{case ((linkId, gradientOption)) => s"$linkId:${gradientOption.getOrElse(0)}"}.mkString(","))
    attr.put(ATTRIBUTE_LINKID_WITH_LENGTH_MAP, linkIdsToLengthOptions.map{case ((linkId, lengthOption)) => s"$linkId:${lengthOption.getOrElse(0)}"}.mkString(","))
    attr.put(ATTRIBUTE_LINKID_WITH_SELECTED_RATE_MAP, linkIdsToSelectedRateOptions.map{case ((linkId, rateOption)) => s"$linkId:${rateOption.getOrElse(0)}"}.mkString(","))
    attr.put(ATTRIBUTE_LINKID_WITH_FINAL_CONSUMPTION_MAP, linkIdsToConsumptionOptions.map{case ((linkId, consumptionOption)) => s"$linkId:${consumptionOption.getOrElse(0)}"}.mkString(","))
    attr.put(ATTRIBUTE_SECONDARY_LINKID_WITH_SELECTED_RATE_MAP, secondaryLinkIdsToSelectedRateOptions.map{case ((linkId, rateOption)) => s"$linkId:${rateOption.getOrElse(0)}"}.mkString(","))
    attr.put(ATTRIBUTE_SECONDARY_LINKID_WITH_FINAL_CONSUMPTION_MAP, secondaryLinkIdsToConsumptionOptions.map{case ((linkId, consumptionOption)) => s"$linkId:${consumptionOption.getOrElse(0)}"}.mkString(","))
       */
      attr.put(ATTRIBUTE_RIDERS, ridersToStr(riders))
      filledAttrs.set(attr)
      attr
    }
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
  /*
  val ATTRIBUTE_LINKID_WITH_LANE_MAP: String = "linkIdToLaneMap"
  val ATTRIBUTE_LINKID_WITH_SPEED_MAP: String = "linkIdToSpeedMap"
  val ATTRIBUTE_LINKID_WITH_SELECTED_GRADIENT_MAP: String = "linkIdToSelectedGradientMap"
  val ATTRIBUTE_LINKID_WITH_LENGTH_MAP: String = "linkIdToLengthMap"
  val ATTRIBUTE_LINKID_WITH_SELECTED_RATE_MAP: String = "primaryLinkIdToSelectedRateMap"
  val ATTRIBUTE_LINKID_WITH_FINAL_CONSUMPTION_MAP: String = "primaryLinkIdToFinalConsumptionMap"
  val ATTRIBUTE_SECONDARY_LINKID_WITH_SELECTED_RATE_MAP: String = "secondaryLinkIdToSelectedRateMap"
  val ATTRIBUTE_SECONDARY_LINKID_WITH_FINAL_CONSUMPTION_MAP: String = "secondaryLinkIdToFinalConsumptionMap"
   */
  val ATTRIBUTE_RIDERS: String = "riders"

  def apply(
    time: Double,
    vehicleId: Id[Vehicle],
    driverId: String,
    vehicleType: BeamVehicleType,
    numPass: Int,
    beamLeg: BeamLeg,
    primaryFuelConsumed: Double,
    secondaryFuelConsumed: Double,
    endLegPrimaryFuelLevel: Double,
    endLegSecondaryFuelLevel: Double,
    amountPaid: Double, /*
    linkIdsToLaneOptions: IndexedSeq[(Int, Option[Int])],
    linkIdsToSpeedOptions: IndexedSeq[(Int, Option[Double])],
    linkIdsToGradientOptions: IndexedSeq[(Int, Option[Double])],
    linkIdsToLengthOptions: IndexedSeq[(Int, Option[Double])],
    linkIdsToSelectedRateOptions: IndexedSeq[(Int, Option[Double])],
    linkIdsToConsumptionOptions: IndexedSeq[(Int, Option[Double])],
    secondaryLinkIdsToSelectedRateOptions: IndexedSeq[(Int, Option[Double])],
    secondaryLinkIdsToConsumptionOptions: IndexedSeq[(Int, Option[Double])]*/
    riders: IndexedSeq[Id[Person]]
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
      /*,
      linkIdsToLaneOptions = linkIdsToLaneOptions,
      linkIdsToSpeedOptions = linkIdsToSpeedOptions,
      linkIdsToGradientOptions = linkIdsToGradientOptions,
      linkIdsToLengthOptions = linkIdsToLengthOptions,
      linkIdsToSelectedRateOptions = linkIdsToSelectedRateOptions,
      linkIdsToConsumptionOptions = linkIdsToConsumptionOptions,
      secondaryLinkIdsToSelectedRateOptions = secondaryLinkIdsToSelectedRateOptions,
      secondaryLinkIdsToConsumptionOptions = secondaryLinkIdsToConsumptionOptions*/
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
    val linkIds: IndexedSeq[Int] = if (linkIdsAsStr == "") IndexedSeq.empty else linkIdsAsStr.split(",").map(_.toInt)
    val linkTravelTimeStr = attr.getOrElse(ATTRIBUTE_LINK_TRAVEL_TIME, "")
    val linkTravelTime: IndexedSeq[Double] =
      if (linkTravelTimeStr == null || linkTravelTimeStr == "") IndexedSeq.empty
      else linkTravelTimeStr.split(",").map(_.toDouble)
    val startX: Double = attr(ATTRIBUTE_START_COORDINATE_X).toDouble
    val startY: Double = attr(ATTRIBUTE_START_COORDINATE_Y).toDouble
    val endX: Double = attr(ATTRIBUTE_END_COORDINATE_X).toDouble
    val endY: Double = attr(ATTRIBUTE_END_COORDINATE_Y).toDouble
    val primaryFuelConsumed: Double = attr(ATTRIBUTE_PRIMARY_FUEL).toDouble
    val secondaryFuelConsumed: Double = attr(ATTRIBUTE_SECONDARY_FUEL).toDouble
    val endLegPrimaryFuelLevel: Double = attr(ATTRIBUTE_END_LEG_PRIMARY_FUEL_LEVEL).toDouble
    val endLegSecondaryFuelLevel: Double = attr(ATTRIBUTE_END_LEG_SECONDARY_FUEL_LEVEL).toDouble
    val amountPaid: Double = attr(ATTRIBUTE_TOLL_PAID).toDouble
    val riders: IndexedSeq[Id[Person]] = ridersFromStr(attr.getOrElse(ATTRIBUTE_RIDERS, ""))
    val fromStopIndex: Option[Int] =
      attr.get(ATTRIBUTE_FROM_STOP_INDEX).flatMap(x => if (x == "") None else Some(x.toInt))
    val toStopIndex: Option[Int] = attr.get(ATTRIBUTE_TO_STOP_INDEX).flatMap(x => if (x == "") None else Some(x.toInt))
    /*
    val linkIdsToLaneOptions = attr(ATTRIBUTE_LINKID_WITH_LANE_MAP).split(",").map(x=>{
      val linkIdToLaneSplit = x.split(":")
      (linkIdToLaneSplit(0).toInt, Some(linkIdToLaneSplit(1).toInt))
    })
    val linkIdsToSpeedOptions = attr(ATTRIBUTE_LINKID_WITH_SPEED_MAP).split(",").map(x=>{
      val linkIdToSpeedSplit = x.split(":")
      (linkIdToSpeedSplit(0).toInt, Some(linkIdToSpeedSplit(1).toDouble))
    })
    val linkIdsToGradientOptions = attr(ATTRIBUTE_LINKID_WITH_SELECTED_GRADIENT_MAP).split(",").map(x=>{
      val linkIdToGradientSplit = x.split(":")
      (linkIdToGradientSplit(0).toInt, Some(linkIdToGradientSplit(1).toDouble))
    })
    val linkIdsToLengthOptions = attr(ATTRIBUTE_LINKID_WITH_LENGTH_MAP).split(",").map(x=>{
      val linkIdToLengthSplit = x.split(":")
      (linkIdToLengthSplit(0).toInt, Some(linkIdToLengthSplit(1).toDouble))
    })
    val linkIdsToSelectedRateOptions = attr(ATTRIBUTE_LINKID_WITH_SELECTED_RATE_MAP).split(",").map(x=>{
      val linkIdToRateSplit = x.split(":")
      (linkIdToRateSplit(0).toInt, Some(linkIdToRateSplit(1).toDouble))
    })
    val linkIdsToConsumptionOptions = attr(ATTRIBUTE_LINKID_WITH_FINAL_CONSUMPTION_MAP).split(",").map(x=>{
      val linkIdToConsumptionSplit = x.split(":")
      (linkIdToConsumptionSplit(0).toInt, Some(linkIdToConsumptionSplit(1).toDouble))
    })
    val secondaryLinkIdsToSelectedRateOptions = attr(ATTRIBUTE_SECONDARY_LINKID_WITH_SELECTED_RATE_MAP).split(",").map(x=>{
      val linkIdToRateSplit = x.split(":")
      (linkIdToRateSplit(0).toInt, Some(linkIdToRateSplit(1).toDouble))
    })
    val secondaryLinkIdsToConsumptionOptions = attr(ATTRIBUTE_SECONDARY_LINKID_WITH_FINAL_CONSUMPTION_MAP).split(",").map(x=>{
      val linkIdToConsumptionSplit = x.split(":")
      (linkIdToConsumptionSplit(0).toInt, Some(linkIdToConsumptionSplit(1).toDouble))
    })
     */
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
      /*,
      linkIdsToLaneOptions,
      linkIdsToSpeedOptions,
      linkIdsToGradientOptions,
      linkIdsToLengthOptions,
      linkIdsToSelectedRateOptions,
      linkIdsToConsumptionOptions,
      secondaryLinkIdsToSelectedRateOptions,
      secondaryLinkIdsToConsumptionOptions*/
      riders
    )
  }

  def ridersFromStr(ridersStr: String): IndexedSeq[Id[Person]] = {
    if (ridersStr.isEmpty) {
      Vector()
    } else {
      ridersStr.split(":").toIndexedSeq.map(Id.create(_, classOf[Person]))
    }
  }

  def ridersToStr(riders: IndexedSeq[Id[Person]]): String = {
    riders.mkString(":")
  }
}
