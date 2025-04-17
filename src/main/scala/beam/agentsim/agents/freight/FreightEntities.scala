package beam.agentsim.agents.freight

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.taz.TAZ
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.immutable

/**
  * @author Dmitry Openkov
  */
case class PayloadType(value: String)

sealed abstract class FreightRequestType extends EnumEntry

object FreightRequestType extends Enum[FreightRequestType] {
  val values: immutable.IndexedSeq[FreightRequestType] = findValues
  case object Unloading extends FreightRequestType
  case object Loading extends FreightRequestType
}

sealed abstract class FreightDeliveryType extends EnumEntry { val value: String }

object FreightDeliveryType extends Enum[FreightDeliveryType] {
  val values: immutable.IndexedSeq[FreightDeliveryType] = findValues
  case object B2B extends FreightDeliveryType { override val value = "b2b" }
  case object B2C extends FreightDeliveryType { override val value = "b2c" }
  case object Whatever extends FreightDeliveryType { override val value = "whatever" }

  def apply(s: String): FreightDeliveryType = {
    if (s.trim.toLowerCase.startsWith(B2B.value) || s.trim.toLowerCase.contains(B2B.value)) B2B
    else if (s.trim.toLowerCase.startsWith(B2C.value) || s.trim.toLowerCase.contains(B2C.value)) B2C
    else Whatever
  }
}

case class FreightTour(tourId: Id[FreightTour], departureTimeInSec: Int, maxTourDurationInSec: Int)

case class PayloadPlan(
  payloadId: Id[PayloadPlan],
  sequenceRank: Int,
  tourId: Id[FreightTour],
  payloadType: Id[PayloadType],
  weightInKg: Double,
  requestType: FreightRequestType,
  activityType: String,
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
  plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]],
  warehouseLocationTaz: Option[Id[TAZ]],
  warehouseLocationUTM: Coord
)
