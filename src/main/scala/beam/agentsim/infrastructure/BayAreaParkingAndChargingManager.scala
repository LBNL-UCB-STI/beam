package beam.agentsim.infrastructure

import org.matsim.utils.objectattributes.ObjectAttributes

class BayAreaParkingAndChargingManager(tazTreeMap:TAZTreeMap, parkingAttributes: ObjectAttributes) extends ParkingAndChargingInfrastructureManager(tazTreeMap,parkingAttributes) {
  override def calcCost(parkingType: String, arrivalTime: Double, parkingDuration: String): Unit = ???

  override def getCheapestParkingInSameTAZ(): Unit = ???
}
