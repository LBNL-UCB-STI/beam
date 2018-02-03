package beam.agentsim.infrastructure

import org.matsim.utils.objectattributes.ObjectAttributes

abstract class ParkingAndChargingInfrastructureManager(tazTreeMap:TAZTreeMap, parkingAttributes: ObjectAttributes) {


  abstract def calcCost(parkingType:String,arrivalTime:Double, parkingDuration:String)

  def getCheapestParkingInSameTAZ()
}
