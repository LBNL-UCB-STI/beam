package beam.agentsim.agents.freight

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory}
import beam.agentsim.infrastructure.taz.TAZ
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.immutable

/**
  * @author Dmitry Openkov
  */
case class PayloadType(value: String)

sealed trait FreightActivityType extends EnumEntry {
  val value: String
  def toLowerCaseString: String = value.toLowerCase
}

object FreightActivityType extends Enum[FreightActivityType] {

  val values: immutable.IndexedSeq[FreightActivityType] = findValues

  case object Unloading extends FreightActivityType { override val value = "unloading" }
  case object Loading extends FreightActivityType { override val value = "loading" }
  case object Warehouse extends FreightActivityType { override val value = "warehouse" }

  def apply(s: String): FreightActivityType = {
    val normalized = s.trim.toLowerCase
    if (normalized.startsWith(Unloading.value) || normalized.contains(Unloading.value)) Unloading
    else if (normalized.startsWith(Loading.value) || normalized.contains(Loading.value)) Loading
    else if (normalized.startsWith(Warehouse.value) || normalized.contains(Warehouse.value)) Warehouse
    else throw new IllegalArgumentException(s"Unknown FreightActivityType: '$s'")
  }
}

sealed trait FreightDemandType extends EnumEntry { val value: String }

object FreightDemandType extends Enum[FreightDemandType] {
  val values: immutable.IndexedSeq[FreightDemandType] = findValues
  case object B2B extends FreightDemandType { override val value = "b2b" }
  case object B2C extends FreightDemandType { override val value = "b2c" }
  case object Whatever extends FreightDemandType { override val value = "whatever" }

  def apply(s: String): FreightDemandType = {
    if (s.trim.toLowerCase.startsWith(B2B.value) || s.trim.toLowerCase.contains(B2B.value)) B2B
    else if (s.trim.toLowerCase.startsWith(B2C.value) || s.trim.toLowerCase.contains(B2C.value)) B2C
    else {
      Whatever
    }
  }
}

case class FreightTour(tourId: Id[FreightTour], departureTimeInSec: Int, maxTourDurationInSec: Int)

case class PayloadPlan(
  payloadId: Id[PayloadPlan],
  sequenceRank: Int,
  tourId: Id[FreightTour],
  payloadType: Id[PayloadType],
  weightInKg: Double,
  activityType: FreightActivityType,
  locationZone: Option[Id[TAZ]],
  locationUTM: Coord,
  estimatedTimeOfArrivalInSec: Int,
  arrivalTimeWindowInSecLower: Int,
  arrivalTimeWindowInSecUpper: Int,
  operationDurationInSec: Int
)

case class FreightCarrier(
  carrierId: Id[FreightCarrier],
  tourMap: Map[Id[BeamVehicle], IndexedSeq[FreightTour]],
  payloadPlans: Map[Id[PayloadPlan], PayloadPlan],
  fleet: Map[Id[BeamVehicle], BeamVehicle],
  fleetDistribution: Map[BeamVehicleType, Double],
  plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]],
  warehouseLocationTaz: Option[Id[TAZ]],
  warehouseLocationUTM: Coord
)

object FreightEntities {
  // Attention: these prefixes are used in the serialization of Ids, so changing them might break compatibility
  // When changing them make sure to modify java classes like: AgentSimToPhysSimPlanConverter.java
  val FREIGHT_ID_PREFIX = "ft"
  val PASSENGER_ID_PREFIX = "pax"
}
