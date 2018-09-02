package beam.agentsim.infrastructure

import beam.agentsim.Resource
import beam.agentsim.infrastructure.ParkingStall.{StallAttributes, StallValues}
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.EmbodiedBeamLeg
import org.matsim.api.core.v01.Id

class ParkingStall(
  val id: Id[ParkingStall],
  val attributes: StallAttributes,
  val location: Location,
  val cost: Double,
  val stallValues: Option[StallValues]
) extends Resource[ParkingStall] {
  override def getId: Id[ParkingStall] = id
}

object ParkingStall {
  case class StallAttributes(
    tazId: Id[TAZ],
    parkingType: ParkingType,
    pricingModel: PricingModel,
    chargingType: ChargingType,
    reservedFor: ReservedParkingType
  )
  case class StallValues(numStalls: Int, feeInCents: Int)

  sealed trait ParkingType
  case object Residential extends ParkingType
  case object Workplace extends ParkingType
  case object Public extends ParkingType
  case object NoOtherExists extends ParkingType

  object ParkingType {

    def fromString(s: String): ParkingType = {
      s match {
        case "Residential"   => Residential
        case "Workplace"     => Workplace
        case "Public"        => Public
        case "NoOtherExists" => NoOtherExists
        case _               => throw new RuntimeException("Invalid case")
      }
    }
  }

  sealed trait ChargingType

  case object NoCharger extends ChargingType
  case object Level1 extends ChargingType
  case object Level2 extends ChargingType
  case object DCFast extends ChargingType
  case object UltraFast extends ChargingType

  object ChargingType {

    def fromString(s: String): ChargingType = {
      s match {
        case "NoCharger" => NoCharger
        case "Level1"    => Level1
        case "Level2"    => Level2
        case "DCFast"    => DCFast
        case "UltraFast" => UltraFast
        case _           => throw new RuntimeException("Invalid case")
      }
    }

    def getChargerPowerInKW(chargerType: ChargingType): Double = {
      chargerType match {
        case NoCharger => 0.0
        case Level1 =>
          1.5
        case Level2 =>
          6.7
        case DCFast =>
          50.0
        case UltraFast =>
          250.0
      }
    }

    def calculateChargingSessionLengthAndEnergyInJoules(
      chargerType: ChargingType,
      currentEnergyLevelInJoule: Double,
      energyCapacityInJoule: Double,
      vehicleChargingLimit: Option[Double],
      sessionDurationLimit: Option[Long]
    ): (Long, Double) = {
      val vehicleChargingLimitActual = vehicleChargingLimit.getOrElse(Double.MaxValue)
      val sessionLengthLimiter = sessionDurationLimit.getOrElse(Long.MaxValue)
      val sessionLength = Math.min(
        sessionLengthLimiter,
        chargerType match {
          case NoCharger => 0L
          case chType if chType == Level1 || chType == Level2 =>
            Math.round(
              (energyCapacityInJoule - currentEnergyLevelInJoule) / 3.6e6 / Math
                .min(vehicleChargingLimitActual, getChargerPowerInKW(chargerType)) * 3600.0
            )
          case chType if chType == DCFast || chType == UltraFast =>
            if (energyCapacityInJoule * 0.8 < currentEnergyLevelInJoule) {
              0L
            } else {
              Math.round(
                (energyCapacityInJoule * 0.8 - currentEnergyLevelInJoule) / 3.6e6 / Math
                  .min(vehicleChargingLimitActual, getChargerPowerInKW(chargerType)) * 3600.0
              )
            }
        }
      )
      val sessionEnergyInJoules = sessionLength.toDouble / 3600.0 * Math.min(
        vehicleChargingLimitActual,
        getChargerPowerInKW(chargerType)
      ) * 3.6e6
      if (sessionLength < 0) {
        val i = 0
      }
      (sessionLength, sessionEnergyInJoules)
    }
  }

  sealed trait ChargingPreference
  case object NoNeed extends ChargingPreference
  case object MustCharge extends ChargingPreference
  case object Opportunistic extends ChargingPreference

  /*
   *  Flat fee means one price is paid independent of time
   *  Block means price is hourly and can change with the amount of time at the spot (e.g. first hour $1, after than $2/hour)
   *
   *  Use block price even if price is a simple hourly price.
   */
  sealed trait PricingModel
  case object Free extends PricingModel
  case object FlatFee extends PricingModel
  case object Block extends PricingModel

  object PricingModel {

    def fromString(s: String): PricingModel = s match {
      case "Free"    => Free
      case "FlatFee" => FlatFee
      case "Block"   => Block
    }
  }

  sealed trait ReservedParkingType
  case object Any extends ReservedParkingType
  case object RideHailManager extends ReservedParkingType

}
