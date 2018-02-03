package beam.agentsim.infrastructure

import org.matsim.utils.objectattributes.ObjectAttributes

abstract class ParkingManager(tazTreeMap:TAZTreeMap, parkingAttributes: ObjectAttributes) {




  // TODO: parking choice and scoring

  // TODO: should we generalize to TAZ level resource and derive parking from that?

  // TODO: make actor and use Resource manager!
    // TODO: manage resources, including book keeping of capacity

  // TODO: allow independent testing of actor

// TODO: tnc surge prices is separate from this.

  abstract def calcCost(parkingType:String,arrivalTime:Double, parkingDuration:String)

  def getCheapestParkingInSameTAZ()
}
