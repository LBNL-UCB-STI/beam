package beam.agentsim.agents




// TODO
// what functions does RHM need?

/*

class Force(startCoord,endCoord){
  getVector();
}

def getForceAt(coordinate, time):Force{
  waitingLocations=getPreviousIteration.AtTime(time).getWaitingLocationsInRadius(coordinate, radius);

  idlingLocations=getPreviousIteration.AtTime(time).getIdlingLocationsInRadius(coordinate, radius);

  val forces=ArrayBuffer[Force]();

  for (waitingLoc <- waitingLocations){
    forces.add(forces);
  }

  for (waitingLoc <- idlingLocations){
    forces.add(forces);
  }

  val finalForce=getSumOfForces(forces)
  return finalForce;
}


def mainRepositioningAlgorithm(time){
  for (vehicle <- idlingVehicle){
    val force=getForceAt(vehicle.location, time + 5min);

    vehicle.repositionTo(force.endCoordinate);
  }
}




def sumOfForces(force: Seq[Force]):Force{

}

def getPullingForceTowardsWaitingPassenger(coordPassenger,waitingTime, coordTNC): Force{
  val f:force = Force(coordTNC,coordPassenger)
  f.scale(waitingTime^2);
  // do we need a maximum force length here?
}


def getRepulsiveForceAwayFromIdlingVehicle(idlingTNC, coordTNC): Force{
  val f:force = Force(idlingTNC,coordTNC)
  f.scale(1/f.length^2);
  // do we need a maximum force length here?
}



*
 */


  // previousIteration.getWaiting()
