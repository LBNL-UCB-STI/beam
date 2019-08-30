package beam.agentsim.events

import java.util

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingType
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

class ParkingUtilityEvent(tick: Double,
                          driverId: Id[Person],
                          vehId: Id[Vehicle],
                          vehicleType: BeamVehicleType,
                          activityType: String,
                          activityDuration: Double,
                          numStallsSeen: Int,
                          numStallsSampled: Int,
                          sampledStallsChargingTypes: Vector[Option[ChargingPointType]],
                          sampledStallsParkingTypes: Vector[ParkingType],
                          sampledStallsCosts: Vector[Double],
                          selectedStallPrice: Double,
                          selectedStallParkingType: ParkingType,
                          selectedStallChargingPointType: ChargingPointType,
                          selectedStallMnlRangeAnxiety: Double,
                          selectedStallMnlParkingPrice: Double,
                          selectedStallMnlDistance: Double,
                          selectedStallMnlResidential: Double
                         )
  extends Event(tick)
    with HasPersonId
    with ScalaEvent {

  import ParkingUtilityEvent._

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = driverId


  override def getAttributes: util.Map[String, String] = {
    val attributes = super.getAttributes
    attributes.put(ATTRIBUTE_DRIVER_ID, driverId.toString)
    attributes.put(ATTRIBUTE_VEHICLE_ID, vehId.toString)
    attributes.put(ATTRIBUTE_VEHICLE_ENGINE_TYPE, vehicleType.toString)
    attributes.put(ATTRIBUTE_ACTIVITY_TYPE, activityType)
    attributes.put(ATTRIBUTE_ACTIVITY_DURATION, activityDuration.toString)
    attributes.put(ATTRIBUTE_NUM_STALLS_SEEN, numStallsSeen.toString)
    attributes.put(ATTRIBUTE_NUM_STALLS_SAMPLED, numStallsSampled.toString)
    attributes.put(ATTRIBUTE_SAMPLED_STALLS_CHARGING_TYPES_DISTRIBUTION, sampledStallsChargingTypeDist)
    attributes.put(ATTRIBUTE_SAMPLED_STALLS_PARKING_TYPES_DISTRIBUTION, sampledStallsParkingTypeDist)
    attributes.put(ATTRIBUTE_SAMPLED_STALLS_COSTS_DISTRIBUTION, sampledStallsCostsDist)
    attributes.put(ATTRIBUTE_SELECTED_STALL_PRICE, selectedStallPrice.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_PARKING_TYPE, selectedStallParkingType.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_CHARGING_POINT_TYPE, selectedStallChargingPointType.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_RANGE_ANXIETY, selectedStallMnlRangeAnxiety.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_PARKING_PRICE, selectedStallMnlParkingPrice.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_DISTANCE, selectedStallMnlDistance.toString)
    attributes.put(ATTRIBUTE_SELECTED_STALL_MNL_RESIDENTIAL, selectedStallMnlResidential.toString)

    attributes
  }

  private val sampledStallsChargingTypeDist: String = "[" + sampledStallsChargingTypes.map(
    _ match {
      case Some(point) => point.toString
      case None => "NoCharger"
    }
  ).groupBy(identity).mapValues(_.size).map(tuple => tuple._1 + ": " + tuple._2).mkString(",") + "]"

  private val sampledStallsParkingTypeDist: String = "[" +
    sampledStallsParkingTypes.groupBy(identity).mapValues(_.size).map(tuple => tuple._1 + ": " + tuple._2).mkString(",") + "]"

  private val sampledStallsCostsDist: String = "[" +
    sampledStallsCosts.groupBy(identity).mapValues(_.size).map(tuple => tuple._1 + ": " + tuple._2).mkString(",") + "]"

}

object ParkingUtilityEvent {
  val EVENT_TYPE: String = "ParkingUtilityEvent"
  val ATTRIBUTE_DRIVER_ID: String = "driver"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle"
  val ATTRIBUTE_VEHICLE_ENGINE_TYPE: String = "vehicleType"
  val ATTRIBUTE_ACTIVITY_TYPE: String = "activityType"
  val ATTRIBUTE_ACTIVITY_DURATION: String = "activityDuration"
  val ATTRIBUTE_NUM_STALLS_SEEN: String = "numStallsSeen"
  val ATTRIBUTE_NUM_STALLS_SAMPLED: String = "numStallsSampled"
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

