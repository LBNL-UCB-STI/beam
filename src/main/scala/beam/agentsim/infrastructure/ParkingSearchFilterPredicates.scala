package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.agents.vehicles.FuelType.Electricity
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZone

object ParkingSearchFilterPredicates {

  /**
    * if the destination activity is "home" then we are a PEV. this function is true when:
    *
    * 1. we are not headed home (i.e. not a PersonAgent driving their car home)
    * 2. if we are headed home,
    *   - and we have an electric engine,
    *   - that we require charging plugs on some probability
    *   - that this zone meets that criteria
    *
    * @param zone
    * @param isPEVAndNeedsToChargeAtHome
    * @param beamVehicleOption
    * @return
    */
  def testPEVChargeWhenHeadedHome(
    zone: ParkingZone,
    isPEVAndNeedsToChargeAtHome: Option[Boolean],
    beamVehicleOption: Option[BeamVehicle]
  ): Boolean =
    isPEVAndNeedsToChargeAtHome match {
      case None => true // not a PEV, any stall is ok
      case Some(needToCharge) =>
        if (!needToCharge) true // don't need to charge, any stall is ok
        else
          beamVehicleOption match {
            case Some(beamVehicle) =>
              beamVehicle.beamVehicleType.primaryFuelType match {
                case Electricity => zone.chargingPointType.nonEmpty
                case _           => true // not a charging car, any stall is ok
              }
            case _ => true // not in a vehicle, any stall is ok
          }
    }

  def rideHailFastChargingOnly(
    zone: ParkingZone,
    activityType: String
  ): Boolean =
    activityType.toLowerCase match {
      case "charge" =>
        zone.chargingPointType match {
          case Some(chargingPointType) => ChargingPointType.isFastCharger(chargingPointType)
          case None                    => false // requiring fast chargers only
        }
      case _ => true // not a ride hail vehicle seeking charging
    }

  def requireStallHasCharger(
    activityType: String,
    beamVehicleOption: Option[BeamVehicle],
  ): Boolean =
    activityType.toLowerCase match {
      case "charge" => true
      case "init"   => false
      case _ =>
        beamVehicleOption match {
          case Some(beamVehicle) =>
            beamVehicle.beamVehicleType.primaryFuelType match {
              case Electricity => true
              case _           => false
            }
          case _ => false
        }
    }

  def canThisCarParkHere(
    parkingZone: ParkingZone,
    returnSpotsWithChargers: Boolean,
    returnSpotsWithoutChargers: Boolean
  ): Boolean = {
    parkingZone.chargingPointType match {
      case Some(_) => returnSpotsWithChargers
      case None    => returnSpotsWithoutChargers
    }
  }
}
