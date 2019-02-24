package beam.agentsim.infrastructure.charging
import beam.agentsim.infrastructure.charging.ElectricCurrent.{AC, DC}

import scala.util.{Failure, Success, Try}

sealed trait ChargingPoint
case object ChargingPoint {

// some standard implementations of currently existing charging points
  case object HouseholdSocket extends ChargingPoint

  case object BlueHouseholdSocket extends ChargingPoint

  case object Cee16ASocket extends ChargingPoint

  case object Cee32ASocket extends ChargingPoint

  case object Cee63ASocket extends ChargingPoint

  case object ChargingStationType1 extends ChargingPoint

  case object ChargingStationType2 extends ChargingPoint

  case object ChargingStationCcsComboType1 extends ChargingPoint

  case object ChargingStationCcsComboType2 extends ChargingPoint

  case object TeslaSuperCharger extends ChargingPoint

// the way to provide custom charging points
  case class CustomChargingPoint(installedCapacity: Double, electricCurrentType: ElectricCurrent) extends ChargingPoint

  case object CustomChargingPoint {

    def apply(installedCapacity: String, electricCurrentType: String): CustomChargingPoint = {
      Try {
        installedCapacity.toDouble
      } match {
        case Failure(_) =>
          throw new IllegalArgumentException(s"provided 'installed capacity' $installedCapacity is invalid.")
        case Success(installedCapacityDouble) =>
          CustomChargingPoint(installedCapacityDouble, ElectricCurrent(electricCurrentType))
      }
    }

  }

  // matches either the standard ones or a custom one
  def apply(s: String): ChargingPoint = {
    s match {
      case "HouseholdSocket"              => HouseholdSocket
      case "BlueHouseholdSocket"          => BlueHouseholdSocket
      case "Cee16ASocket"                 => Cee16ASocket
      case "Cee32ASocket"                 => Cee32ASocket
      case "Cee63ASocket"                 => Cee63ASocket
      case "ChargingStationType1"         => ChargingStationType1
      case "ChargingStationType2"         => ChargingStationType2
      case "ChargingStationCcsComboType1" => ChargingStationCcsComboType1
      case "ChargingStationCcsComboType2" => ChargingStationCcsComboType2
      case "TeslaSuperCharger"            => TeslaSuperCharger
      case _                              => throw new IllegalArgumentException("invalid argument")
    }
  }

  // matches either the standard ones or a custom one
  def getChargingPointInstalledPowerInKw(chargingPoint: ChargingPoint): Double = {
    chargingPoint match {
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
      case CustomChargingPoint(v, _)    => v
      case _                            => throw new IllegalArgumentException("invalid argument")
    }
  }

  def getChargingPointCurrent(chargingPoint: ChargingPoint): ElectricCurrent = {
    chargingPoint match {
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
      case CustomChargingPoint(_, c)    => c
      case _                            => throw new IllegalArgumentException("invalid argument")
    }
  }

  def calculateChargingSessionLengthAndEnergyInJoule(
    chargingPoint: ChargingPoint,
    currentEnergyLevelInJoule: Double,
    batteryCapacityInJoule: Double,
    vehicleAcChargingLimitsInWatts: Double,
    vehicleDcChargingLimitsInWatts: Double,
    sessionDurationLimit: Option[Long]
  ): (Long, Double) = {
    val vehicleChargingLimitActualInKW = ChargingPoint.getChargingPointCurrent(chargingPoint) match {
      case AC => vehicleAcChargingLimitsInWatts / 1000.0
      case DC => vehicleDcChargingLimitsInWatts / 1000.0
    }
    val sessionLengthLimiter = sessionDurationLimit.getOrElse(Long.MaxValue)
    val sessionLength = Math.min(
      sessionLengthLimiter,
      Math.round(
        (batteryCapacityInJoule - currentEnergyLevelInJoule) / 3.6e6 / Math
          .min(vehicleChargingLimitActualInKW, ChargingPoint.getChargingPointInstalledPowerInKw(chargingPoint)) * 3600.0
      )
    )
    val sessionEnergyInJoules = sessionLength.toDouble / 3600.0 * Math.min(
      vehicleChargingLimitActualInKW,
      ChargingPoint.getChargingPointInstalledPowerInKw(chargingPoint)
    ) * 3.6e6
    (sessionLength, sessionEnergyInJoules)
  }
}
