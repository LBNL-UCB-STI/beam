package beam.agentsim.infrastructure

import org.matsim.utils.objectattributes.ObjectAttributes

abstract class ParkingManager(tazTreeMap: TAZTreeMap,
                              parkingAttributes: ObjectAttributes) {

// TODO: get closest non-full parking taz of a certain type.

  // TODO: how to handle private parking?

  // TODO: parking choice and scoring

  // TODO: should we generalize to TAZ level resource and derive parking from that?

  // TODO: make actor and use Resource manager!
  // TODO: manage resources, including book keeping of capacity

  // TODO: allow independent testing of actor -> TODO: create tests.

// TODO: tnc surge prices is separate from this.

  // abstract def calcCost(parkingType:String,arrivalTime:Double, parkingDuration:String)

  //def getCheapestParkingInSameTAZ()

  //def getParkingAttribute(parkingType: ParkingType,parkingAttibute:ParkingAttribute,taz:TAZ)
}
