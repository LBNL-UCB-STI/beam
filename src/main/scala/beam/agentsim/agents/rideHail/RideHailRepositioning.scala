package beam.agentsim.agents.rideHail

import beam.agentsim.events.SpaceTime


// TODO
// what functions does RHM need?

/*

class Force(startCoord,endCoord){
  getVector()
}

def getForceAt(coordinate, time):Force{
  waitingLocations=getPreviousIteration.AtTime(time).getWaitingLocationsInRadius(coordinate, radius)

  idlingLocations=getPreviousIteration.AtTime(time).getIdlingLocationsInRadius(coordinate, radius)

  val forces=ArrayBuffer[Force]()

  for (waitingLoc <- waitingLocations){
    forces.add(forces)
  }

  for (waitingLoc <- idlingLocations){
    forces.add(forces)
  }

  val finalForce=getSumOfForces(forces)
  return finalForce
}


def mainRepositioningAlgorithm(time){
  for (vehicle <- idlingVehicle){
    val force=getForceAt(vehicle.location, time + 5min)

    vehicle.repositionTo(force.endCoordinate)
  }
}




def sumOfForces(force: Seq[Force]):Force{

}

def getPullingForceTowardsWaitingPassenger(coordPassenger,waitingTime, coordTNC): Force{
  val f:force = Force(coordTNC,coordPassenger)
  f.scale(waitingTime^2)
  // do we need a maximum force length here?
}


def getRepulsiveForceAwayFromIdlingVehicle(idlingTNC, coordTNC): Force{
  val f:force = Force(idlingTNC,coordTNC)
  f.scale(1/f.length^2)
  // do we need a maximum force length here?
}



*
 */


  // previousIteration.getWaiting()

case class WaitingEvent(location: SpaceTime, waitingDuration: Double)

class LocationWaitingTimeMatrix(val waitingEvents: Set[WaitingEvent]){

  /*
  TODO: if code is slow, implement version with bins (which has a larger memory foot print)

  timeBinDurationInSec:Double

  val waitingEventBins: ArrayBuffer[Set[WaitingEvent]] = new ArrayBuffer[Set[WaitingEvent]]()

  waitingEvents.foreach{waitingEvent: WaitingEvent =>
    waitingEventBins.
  }

*/

  def getWaitingEventsAtTime(time: Double):Set[WaitingEvent] ={
    waitingEvents.filter(waitingEvent => time >= waitingEvent.location.time && time <= waitingEvent.location.time + waitingEvent.waitingDuration)
  }
}




class IterationHistory(){


}



// TODO: collect location, when, waiting time info.
// TODO: collect location, when idling time.

