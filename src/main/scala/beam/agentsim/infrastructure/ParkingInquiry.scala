package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.VehicleManager.ReservedFor
import beam.agentsim.agents.vehicles.{BeamVehicle, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingInquiry.{ParkingActivityType, ParkingSearchMode}
import beam.agentsim.infrastructure.parking.{ParkingMNL, ParkingType}
import beam.agentsim.scheduler.HasTriggerId
import beam.utils.ParkingManagerIdGenerator
import com.typesafe.scalalogging.LazyLogging
import enumeratum.{Enum, EnumEntry}
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}

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
  searchMode: ParkingSearchMode = ParkingSearchMode.Parking,
  originUtm: Option[SpaceTime] = None,
  triggerId: Long
) extends HasTriggerId {
  val parkingActivityType: ParkingActivityType = ParkingActivityType.fromString(activityType)

  val departureLocation: Option[Coord] = searchMode match {
    case ParkingSearchMode.EnRouteCharging => beamVehicle.map(_.spaceTime).orElse(originUtm).map(_.loc)
    case _                                 => None
  }
}

object ParkingInquiry extends LazyLogging {
  sealed abstract class ParkingActivityType extends EnumEntry
  sealed abstract class ParkingSearchMode extends EnumEntry

  object ParkingSearchMode extends Enum[ParkingSearchMode] {
    val values: immutable.IndexedSeq[ParkingSearchMode] = findValues
    case object EnRouteCharging extends ParkingSearchMode
    case object DestinationCharging extends ParkingSearchMode
    case object Parking extends ParkingSearchMode
    case object DoubleParkingAllowed extends ParkingSearchMode
    case object Init extends ParkingSearchMode
  }

  object ParkingActivityType extends Enum[ParkingActivityType] {
    val values: immutable.IndexedSeq[ParkingActivityType] = findValues
    case object Charging extends ParkingActivityType
    case object Miscellaneous extends ParkingActivityType
    case object Home extends ParkingActivityType
    case object Working extends ParkingActivityType
    case object Idling extends ParkingActivityType
    case object Hotelling extends ParkingActivityType
    case object LoadingUnloading extends ParkingActivityType
    case object Warehousing extends ParkingActivityType

    def fromString(activityType: String): ParkingActivityType = {
      activityType.toLowerCase match {
        case "home" | "residential"                     => ParkingActivityType.Home
        case "work" | "workplace"                       => ParkingActivityType.Working
        case "charge" | "charging"                      => ParkingActivityType.Charging
        case "wherever" | "public" | "miscellaneous"    => ParkingActivityType.Miscellaneous
        case "idle" | "idling" | "hotelling"            => ParkingActivityType.Idling
        case "hotelling" | "overnight"                  => ParkingActivityType.Hotelling
        case "commercial" | "loading" | "unloading"     => ParkingActivityType.LoadingUnloading
        case "depot" | "warehouse" | "warehousing"      => ParkingActivityType.Warehousing
        case otherType if otherType.contains("enroute") => ParkingActivityType.Charging
        case otherType if otherType.contains("home")    => ParkingActivityType.Home
        case otherType if otherType.contains("work")    => ParkingActivityType.Working
        case otherType if otherType.contains("loading") => ParkingActivityType.LoadingUnloading
        case otherType =>
          logger.debug(s"This Parking Activity Type ($otherType) has not been defined")
          ParkingActivityType.Miscellaneous
      }
    }

    def fromParkingType(parkingType: ParkingType): ParkingActivityType = fromString(parkingType.toString)
  }

  def init(
    destinationUtm: SpaceTime,
    activityType: String,
    reservedFor: ReservedFor = VehicleManager.AnyManager,
    beamVehicle: Option[BeamVehicle] = None,
    remainingTripData: Option[ParkingMNL.RemainingTripData] = None,
    personId: Option[Id[Person]] = None,
    valueOfTime: Double = 0.0,
    parkingDuration: Double = 60.0,
    reserveStall: Boolean = true,
    requestId: Int = ParkingManagerIdGenerator.nextId,
    searchMode: ParkingSearchMode = ParkingSearchMode.Parking,
    originUtm: Option[SpaceTime] = None,
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
      searchMode,
      originUtm,
      triggerId = triggerId
    )
}
