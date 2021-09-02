package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.charging.ChargingPointType.ChargerTypeEnum
import beam.agentsim.infrastructure.parking.ResidentialParking.ResidentialParkingInquiry
import org.matsim.api.core.v01.Id
import org.matsim.households.Household

import java.util.concurrent.ConcurrentHashMap

class ResidentialParking(
  householdId: Id[Household],
  numParkingStalls: Int,
  numLevel1Chargers: Int,
  numLevel2Chargers: Int
) {

  def howManyStallsAreThere: Int = numParkingStalls + numLevel1Chargers + numLevel2Chargers

  def stallsAvailable(chargingPointType: Option[ChargingPointType]): Option[Int] = {
    ChargingPointType.getChargingType(chargingPointType) match {
      case ChargerTypeEnum.NoCharger  => Some(numParkingStalls - parkingStallsAvailable.size())
      case ChargerTypeEnum.HomeLevel1 => Some(numLevel1Chargers - level1ChargersAvailable.size())
      case ChargerTypeEnum.HomeLevel2 => Some(numLevel2Chargers - level2ChargersAvailable.size())
      case _                          => None
    }
  }

  def claimStall(
    chargingPointType: Option[ChargingPointType],
    residentialParkingInquiry: ResidentialParkingInquiry
  ): Unit = {
    ChargingPointType.getChargingType(chargingPointType) match {
      case ChargerTypeEnum.NoCharger if parkingStallsAvailable.size() < numParkingStalls =>
        parkingStallsAvailable.put(residentialParkingInquiry.beamVehicle.id, residentialParkingInquiry.beamVehicle)
      case ChargerTypeEnum.HomeLevel1 if level1ChargersAvailable.size() < numLevel1Chargers =>
        level1ChargersAvailable.put(residentialParkingInquiry.beamVehicle.id, residentialParkingInquiry.beamVehicle)
      case ChargerTypeEnum.HomeLevel2 if level2ChargersAvailable.size() < numLevel2Chargers =>
        level2ChargersAvailable.put(residentialParkingInquiry.beamVehicle.id, residentialParkingInquiry.beamVehicle)
      case _ =>
    }
  }

  def releaseStall(
    chargingPointType: Option[ChargingPointType],
    residentialParkingInquiry: ResidentialParkingInquiry
  ): Unit = {
    ChargingPointType.getChargingType(chargingPointType) match {
      case ChargerTypeEnum.NoCharger if parkingStallsAvailable.size() > 0 =>
        parkingStallsAvailable.remove(residentialParkingInquiry.beamVehicle.id)
      case ChargerTypeEnum.HomeLevel1 if level1ChargersAvailable.size() > 0 =>
        level1ChargersAvailable.remove(residentialParkingInquiry.beamVehicle.id)
      case ChargerTypeEnum.HomeLevel2 if level2ChargersAvailable.size() > 0 =>
        level2ChargersAvailable.remove(residentialParkingInquiry.beamVehicle.id)
      case _ =>
    }
  }

  private[ParkingZone] var parkingStallsAvailable =
    new ConcurrentHashMap[Id[BeamVehicle], BeamVehicle](numParkingStalls)

  private[ParkingZone] var level1ChargersAvailable =
    new ConcurrentHashMap[Id[BeamVehicle], BeamVehicle](numLevel1Chargers)

  private[ParkingZone] var level2ChargersAvailable =
    new ConcurrentHashMap[Id[BeamVehicle], BeamVehicle](numLevel2Chargers)

  override def toString =
    s"householdId: $householdId, " +
    s"numParkingStalls: (${parkingStallsAvailable.size()}/$numParkingStalls), " +
    s"numLevel1Chargers: (${level1ChargersAvailable.size()}/$numLevel1Chargers), " +
    s"numLevel2Chargers: (${level2ChargersAvailable.size()}/$numLevel2Chargers)"
}

object ResidentialParking {
  case class ResidentialParkingInquiry(householdId: Id[Household], beamVehicle: BeamVehicle)
}
