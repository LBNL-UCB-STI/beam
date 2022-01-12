package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingInquiry.{activityTypeStringToEnum, ParkingActivityType}
import beam.agentsim.infrastructure.parking.ParkingMNL
import beam.agentsim.scheduler.HasTriggerId
import beam.utils.ParkingManagerIdGenerator
import com.typesafe.scalalogging.LazyLogging
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person

import scala.collection.immutable

/**
  * message sent from a ChoosesParking agent to a Parking Manager to request parking
  *
  * @param destinationUtm  the location where we are seeking nearby parking
  * @param activityType    the activity that the agent will partake in after parking
  * @param beamVehicle     an optional vehicle type (if applicable)
  * @param remainingTripData if vehicle can charge, this has the remaining range/tour distance data
  * @param valueOfTime     the value of time for the requestor
  * @param parkingDuration the duration an agent is parking for
  * @param reserveStall    whether or not we reserve a stall when we send this inquiry. used when simply requesting a cost estimate for parking.
  * @param requestId       a unique ID generated for this inquiry
  */
case class ParkingInquiry(
  destinationUtm: SpaceTime,
  activityType: String,
  reservedFor: ReservedFor = VehicleManager.AnyManager,
  beamVehicle: Option[BeamVehicle] = None,
  remainingTripData: Option[ParkingMNL.RemainingTripData] = None,
  personId: Option[Id[Person]] = None,
  valueOfTime: Double = 0.0,
  parkingDuration: Double = 0,
  reserveStall: Boolean = true,
  requestId: Int =
    ParkingManagerIdGenerator.nextId, // note, this expects all Agents exist in the same JVM to rely on calling this singleton
  triggerId: Long
) extends HasTriggerId {
  val parkingActivityType: ParkingActivityType = activityTypeStringToEnum(activityType)
}

object ParkingInquiry extends LazyLogging {
  sealed abstract class ParkingActivityType extends EnumEntry

  object ParkingActivityType extends Enum[ParkingActivityType] {
    val values: immutable.IndexedSeq[ParkingActivityType] = findValues

    case object Charge extends ParkingActivityType
    case object Init extends ParkingActivityType
    case object Wherever extends ParkingActivityType
    case object Home extends ParkingActivityType
    case object Work extends ParkingActivityType
    case object Secondary extends ParkingActivityType
  }

  def activityTypeStringToEnum(activityType: String): ParkingActivityType = {
    activityType.toLowerCase match {
      case "home"     => ParkingActivityType.Home
      case "init"     => ParkingActivityType.Init
      case "work"     => ParkingActivityType.Work
      case "charge"   => ParkingActivityType.Charge
      case "wherever" => ParkingActivityType.Wherever
      case otherType =>
        logger.debug(s"This Parking Activity Type ($otherType) has not been defined")
        ParkingActivityType.Wherever
    }
  }

  def init(
    destinationUtm: SpaceTime,
    activityType: String,
    reservedFor: ReservedFor = VehicleManager.AnyManager,
    beamVehicle: Option[BeamVehicle] = None,
    remainingTripData: Option[ParkingMNL.RemainingTripData] = None,
    personId: Option[Id[Person]] = None,
    valueOfTime: Double = 0.0,
    parkingDuration: Double = 0,
    reserveStall: Boolean = true,
    requestId: Int = ParkingManagerIdGenerator.nextId,
    triggerId: Long
  ): ParkingInquiry =
    ParkingInquiry(
      destinationUtm,
      activityType,
      reservedFor,
      beamVehicle,
      remainingTripData,
      personId,
      valueOfTime,
      parkingDuration,
      reserveStall,
      requestId,
      triggerId = triggerId
    )
}
