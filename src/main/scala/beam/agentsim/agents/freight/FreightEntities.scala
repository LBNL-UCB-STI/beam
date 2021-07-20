package beam.agentsim.agents.freight

import beam.agentsim.agents.vehicles.BeamVehicle
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle

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

case class FreightTour(
  tourId: Id[FreightTour],
  departureTimeInSec: Int,
  warehouseLocation: Coord,
  maxTourDurationInSec: Int
)

case class PayloadPlan(
  payloadId: Id[PayloadPlan],
  sequenceRank: Int,
  tourId: Id[FreightTour],
  payloadType: Id[PayloadType],
  weight: Double,
  requestType: FreightRequestType,
  location: Coord,
  estimatedTimeOfArrivalInSec: Int,
  arrivalTimeWindowInSec: Int,
  operationDurationInSec: Int
)

case class FreightCarrier(
  carrierId: Id[FreightCarrier],
  tourMap: Map[Id[BeamVehicle], IndexedSeq[FreightTour]],
  payloadPlans: Map[Id[PayloadPlan], PayloadPlan],
  fleet: Map[Id[BeamVehicle], BeamVehicle],
  plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]]
)
