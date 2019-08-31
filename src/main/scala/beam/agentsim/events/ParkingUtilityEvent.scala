package beam.agentsim.events

import java.util

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.{ParkingMNL, ParkingType}
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ParkingZoneSearchStats
import org.matsim.api.core.v01.events.Event

case class ParkingUtilityEvent(
                                driverId: String,
                                beamVehicle: Option[BeamVehicle],
                                activityType: String,
                                parkingDuration: Double,
                                agentVoT: Double,
                                parkingZoneSearchStats: ParkingZoneSearchStats,
                                selectedStallPrice: Double,
                                selectedStallParkingType: ParkingType,
                                selectedStallChargingPointType: Option[ChargingPointType],
                                tick: Double = -1 // todo maybe we need the currentTick? If yes we have to get it into ZonalParkingManager
                              ) extends Event(tick)
  with ScalaEvent {

  import ParkingUtilityEvent._

  private lazy val sampledStallsChargingTypeDist: String = "[" + parkingZoneSearchStats.sampledStallsChargingTypes
    .map {
      case Some(point) => point.toString
      case None => "NoCharger"
    }
    .groupBy(identity)
    .mapValues(_.size)
    .map(tuple => tuple._1 + ": " + tuple._2)
    .mkString(",") + "]"

  private lazy val sampledStallsParkingTypeDist: String = "[" +
    parkingZoneSearchStats.sampledStallsParkingTypes
      .groupBy(identity)
      .mapValues(_.size)
      .map(tuple => tuple._1 + ": " + tuple._2)
      .mkString(",") + "]"

  private lazy val sampledStallsCostsDist: String = "[" +
    parkingZoneSearchStats.sampledStallsCosts.groupBy(identity).mapValues(_.size).map(tuple => tuple._1 + ": " + tuple._2).mkString(",") + "]"

  private lazy val vehIdString: String = beamVehicle match {
    case Some(vehicle) => vehicle.id.toString
    case None => "no beamVehicle in parking inquiry"
  }

  private lazy val vehicleTypeString: String = beamVehicle match {
    case Some(vehicle) => vehicle.beamVehicleType.toString
    case None => "no beamVehicle in parking inquiry"
  }

  private lazy val selectedStallMnlRangeAnxiety = parkingZoneSearchStats.selectedStallMnlParams.getOrElse(ParkingMNL.Parameters.RangeAnxietyCost, "no range anxiety costs provided")
  private lazy val selectedStallMnlParkingPrice = parkingZoneSearchStats.selectedStallMnlParams.getOrElse(ParkingMNL.Parameters.ParkingTicketCost, "no parking costs provided")
  private lazy val selectedStallMnlDistance = parkingZoneSearchStats.selectedStallMnlParams.getOrElse(ParkingMNL.Parameters.WalkingEgressCost, "no walking costs provided")
  private lazy val selectedStallMnlResidential = parkingZoneSearchStats.selectedStallMnlParams.getOrElse(ParkingMNL.Parameters.HomeActivityPrefersResidentialParking, "no home costs provided")

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_DRIVER_ID, driverId)
    attributes.put(ATTRIBUTE_VEHICLE_ID, vehIdString)
    attributes.put(ATTRIBUTE_VEHICLE_ENGINE_TYPE, vehicleTypeString)
    attributes.put(ATTRIBUTE_ACTIVITY_TYPE, activityType)
    attributes.put(ATTRIBUTE_ACTIVITY_DURATION, parkingDuration.toString)
    attributes.put(ATTRIBUTE_AGENT_VOT, agentVoT.toString)
    attributes.put(ATTRIBUTE_NUM_SEARCH_ITERATIONS, parkingZoneSearchStats.numSearchIterations.toString)
    attributes.put(ATTRIBUTE_NUM_PARKING_ZONE_IDS_SEEN, parkingZoneSearchStats.parkingZoneIdsSeen.length.toString)
    attributes.put(ATTRIBUTE_NUM_PARKING_ZONE_IDS_SAMPLED, parkingZoneSearchStats.parkingZoneIdsSeen.length.toString)
    attributes.put(ATTRIBUTE_SAMPLED_STALLS_CHARGING_TYPES_DISTRIBUTION, sampledStallsChargingTypeDist)
    attributes.put(ATTRIBUTE_SAMPLED_STALLS_PARKING_TYPES_DISTRIBUTION, sampledStallsParkingTypeDist)
    attributes.put(ATTRIBUTE_SAMPLED_STALLS_COSTS_DISTRIBUTION, sampledStallsCostsDist)
    attributes.put(ATTRIBUTE_SELECTED_STALL_PRICE, selectedStallPrice.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_PARKING_TYPE, selectedStallParkingType.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_CHARGING_POINT_TYPE, selectedStallChargingPointType.getOrElse("NoCharger").toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_RANGE_ANXIETY, selectedStallMnlRangeAnxiety.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_PARKING_PRICE, selectedStallMnlParkingPrice.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_DISTANCE, selectedStallMnlDistance.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_RESIDENTIAL, selectedStallMnlResidential.toString)
    attributes
  }

}

case object ParkingUtilityEvent {
  val EVENT_TYPE: String = "ParkingUtilityEvent"
  val ATTRIBUTE_DRIVER_ID: String = "driver"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_VEHICLE_ENGINE_TYPE: String = "vehicleType"
  val ATTRIBUTE_ACTIVITY_TYPE: String = "activityType"
  val ATTRIBUTE_ACTIVITY_DURATION: String = "parkingDuration"
  val ATTRIBUTE_AGENT_VOT: String = "agentValueOfTime"
  val ATTRIBUTE_NUM_SEARCH_ITERATIONS: String = "numSearchIterations"
  val ATTRIBUTE_NUM_PARKING_ZONE_IDS_SEEN: String = "numParkingZonesSeen"
  val ATTRIBUTE_NUM_PARKING_ZONE_IDS_SAMPLED: String = "numParkingZonesSampled"
  val ATTRIBUTE_SAMPLED_STALLS_CHARGING_TYPES_DISTRIBUTION: String = "sampledStallsChargingTypesDistribution"
  val ATTRIBUTE_SAMPLED_STALLS_PARKING_TYPES_DISTRIBUTION: String = "sampledStallsParkingTypesDistribution"
  val ATTRIBUTE_SAMPLED_STALLS_COSTS_DISTRIBUTION: String = "sampledStallsCostsDistribution"
  val ATTRIBUTE_SELECTED_STALL_PRICE: String = "selectedStallPrice"
  val ATTRIBUTE_SELECTED_STALL_PARKING_TYPE: String = "selectedStallParkingType"
  val ATTRIBUTE_SELECTED_STALL_CHARGING_POINT_TYPE: String = "selectedStallChargingPointType"
  val ATTRIBUTE_SELECTED_STALL_MNL_RANGE_ANXIETY: String = "selectedStallMnlRangeAnxiety"
  val ATTRIBUTE_SELECTED_STALL_MNL_PARKING_PRICE: String = "selectedStallMnlParkingPrice"
  val ATTRIBUTE_SELECTED_STALL_MNL_DISTANCE: String = "selectedStallMnlDistance"
  val ATTRIBUTE_SELECTED_STALL_MNL_RESIDENTIAL: String = "selectedStallMnlResidential"
}

