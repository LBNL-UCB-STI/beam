package beam.agentsim.infrastructure

import akka.actor.Actor
import beam.agentsim.ResourceManager
import beam.agentsim.agents.PersonAgent
import beam.agentsim.infrastructure.ParkingStall.ChargingPreference
import beam.router.BeamRouter.Location
import beam.router.RoutingModel.BeamTime
import org.matsim.api.core.v01.Id
import org.matsim.utils.objectattributes.ObjectAttributes

abstract class ParkingManager(tazTreeMap:TAZTreeMap, parkingAttributes: ObjectAttributes) extends Actor with ResourceManager[ParkingStall]{



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

object ParkingManager{
  case class ParkingInquiry(customerId: Id[PersonAgent], customerLocation: Location, destination: Location,
                            activityType: String, valueOfTime: Double, chargingPreference: ChargingPreference,
                            arrivalTime: Long, parkingDuration: Double)
  case class ParkingInquiryResponse(stall: ParkingStall)
}
