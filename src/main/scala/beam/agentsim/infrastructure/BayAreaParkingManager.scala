package beam.agentsim.infrastructure

import org.matsim.utils.objectattributes.ObjectAttributes

abstract class BayAreaParkingManager(tazTreeMap: TAZTreeMap,
                                     parkingAttributes: ObjectAttributes)
    extends ParkingManager(tazTreeMap, parkingAttributes) {
  // override def calcCost(parkingType: String, arrivalTime: Double, parkingDuration: String): Unit = ???

  // override def getCheapestParkingInSameTAZ(): Unit = ???
}
