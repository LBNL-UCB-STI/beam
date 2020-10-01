package beam.agentsim.infrastructure.charging

import beam.agentsim.infrastructure.charging.ElectricCurrentType.{AC, DC}

import scala.util.matching.Regex
import scala.util.{Failure, Success, Try}

sealed trait ChargingPointType

object ChargingPointType {

  // some standard implementations of currently existing charging points
  case object HouseholdSocket extends ChargingPointType

  case object BlueHouseholdSocket extends ChargingPointType

  case object Cee16ASocket extends ChargingPointType

  case object Cee32ASocket extends ChargingPointType

  case object Cee63ASocket extends ChargingPointType

  case object ChargingStationType1 extends ChargingPointType

  case object ChargingStationType2 extends ChargingPointType

  case object ChargingStationCcsComboType1 extends ChargingPointType

  case object ChargingStationCcsComboType2 extends ChargingPointType

  case object TeslaSuperCharger extends ChargingPointType

  val AllFormalChargingPointTypes = List(
    HouseholdSocket,
    BlueHouseholdSocket,
    Cee16ASocket,
    Cee32ASocket,
    Cee63ASocket,
    ChargingStationType1,
    ChargingStationType2,
    ChargingStationCcsComboType1,
    ChargingStationCcsComboType2,
    TeslaSuperCharger
  )

  // provide custom charging points
  case class CustomChargingPoint(id: String, installedCapacity: Double, electricCurrentType: ElectricCurrentType)
      extends ChargingPointType {
    override def toString: String = s"$id($installedCapacity|$electricCurrentType)"
  }

  object CustomChargingPoint {

    def apply(id: String, installedCapacity: String, electricCurrentType: String): CustomChargingPoint = {
      Try {
        installedCapacity.toDouble
      } match {
        case Failure(_) =>
          throw new IllegalArgumentException(s"provided 'installed capacity' $installedCapacity is invalid.")
        case Success(installedCapacityDouble) =>
          CustomChargingPoint(id, installedCapacityDouble, ElectricCurrentType(electricCurrentType.toUpperCase))
      }
    }

  }

  private[ChargingPointType] val CustomChargingPointRegex: Regex =
    """(\w+\d*)\s*\(\s*(\d+\.?\d+)\s*\|\s*(\w{2})\s*\)""".r.unanchored

  // matches either the standard ones or a custom one
  // these were breaking some tests with a ChargingPoint parsing error caused by Event handlers
  def apply(s: String): Option[ChargingPointType] = {
    s.trim.toLowerCase match {
      case "householdsocket"              => Some(HouseholdSocket)
      case "bluehouseholdsocket"          => Some(BlueHouseholdSocket)
      case "cee16asocket"                 => Some(Cee16ASocket)
      case "cee32asocket"                 => Some(Cee32ASocket)
      case "cee63asocket"                 => Some(Cee63ASocket)
      case "chargingstationtype1"         => Some(ChargingStationType1)
      case "chargingstationtype2"         => Some(ChargingStationType2)
      case "chargingstationccscombotype1" => Some(ChargingStationCcsComboType1)
      case "chargingstationccscombotype2" => Some(ChargingStationCcsComboType2)
      case "teslasupercharger"            => Some(TeslaSuperCharger)
      case "nocharger" | "none" | ""      => None
      case CustomChargingPointRegex(id, installedCapacity, currentType) =>
        Some(CustomChargingPoint(id, installedCapacity, currentType))
      case _ =>
        None
        throw new IllegalArgumentException("invalid argument for ChargingPointType: " + s.trim.toLowerCase)
    }
  }

  // matches either the standard ones or a custom one
  def getChargingPointInstalledPowerInKw(chargingPointType: ChargingPointType): Double = {
    chargingPointType match {
      case HouseholdSocket              => 2.3
      case BlueHouseholdSocket          => 3.6
      case Cee16ASocket                 => 11
      case Cee32ASocket                 => 22
      case Cee63ASocket                 => 43
      case ChargingStationType1         => 7.2
      case ChargingStationType2         => 43
      case ChargingStationCcsComboType1 => 11
      case ChargingStationCcsComboType2 => 50
      case TeslaSuperCharger            => 135
      case CustomChargingPoint(_, v, _) => v
      case _                            => throw new IllegalArgumentException("invalid argument")
    }
  }

  def getChargingPointCurrent(chargingPointType: ChargingPointType): ElectricCurrentType = {
    chargingPointType match {
      case HouseholdSocket              => AC
      case BlueHouseholdSocket          => AC
      case Cee16ASocket                 => AC
      case Cee32ASocket                 => AC
      case Cee63ASocket                 => AC
      case ChargingStationType1         => AC
      case ChargingStationType2         => AC
      case ChargingStationCcsComboType1 => DC
      case ChargingStationCcsComboType2 => DC
      case TeslaSuperCharger            => DC
      case CustomChargingPoint(_, _, c) => c
      case _                            => throw new IllegalArgumentException("invalid argument")
    }
  }

  def calculateChargingSessionLengthAndEnergyInJoule(
    chargingPointType: ChargingPointType,
    currentEnergyLevelInJoule: Double,
    batteryCapacityInJoule: Double,
    vehicleAcChargingLimitsInWatts: Double,
    vehicleDcChargingLimitsInWatts: Double,
    sessionDurationLimit: Option[Long],
    chargingPowerLimit: Option[Double]
  ): (Long, Double) = {
    val chargingLimits = ChargingPointType.getChargingPointCurrent(chargingPointType) match {
      case AC => (vehicleAcChargingLimitsInWatts / 1000.0, batteryCapacityInJoule)
      case DC =>
        (vehicleDcChargingLimitsInWatts / 1000.0, batteryCapacityInJoule * 0.8) // DC limits charging to 0.8 * battery capacity
    }
    val sessionLengthLimiter = sessionDurationLimit.getOrElse(Long.MaxValue)
    val chargingPower = ChargingPointType.getChargingPointInstalledPowerInKw(chargingPointType)
    val chargingPowerLimiter = Math.min(chargingPowerLimit.getOrElse(chargingPower), chargingPower)
    val sessionLength = Math.max(
      Math.min(
        sessionLengthLimiter,
        Math.round(
          (chargingLimits._2 - currentEnergyLevelInJoule) / 3.6e6 / Math
            .min(chargingLimits._1, chargingPowerLimiter) * 3600.0
        )
      ),
      0
    )
    val sessionEnergyInJoules = sessionLength.toDouble / 3600.0 * Math.min(chargingLimits._1, chargingPowerLimiter) * 3.6e6
    (sessionLength, sessionEnergyInJoules)
  }

  // used to identify fast chargers
  val FastChargingThreshold: Double = 20.0

  /**
    * recognizes fast charger ChargingPointTypes
    * @param chargingPointType a chargingPointType
    * @return if it is "fast"
    */
  def isFastCharger(chargingPointType: ChargingPointType): Boolean =
    getChargingPointInstalledPowerInKw(chargingPointType) >= FastChargingThreshold

}
