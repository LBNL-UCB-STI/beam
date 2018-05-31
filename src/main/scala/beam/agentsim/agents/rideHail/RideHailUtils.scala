package beam.agentsim.agents.rideHail

import beam.router.RoutingModel.BeamLeg
import org.matsim.api.core.v01.Coord

object RideHailUtils {

  def getUpdatedLeg


  private def getVehicleCoordinateForInterruptedLeg(beamLeg:BeamLeg, stopTime:Double): Coord ={
    // TODO: implement following solution following along links
    /*
    var currentTime=beamLeg.startTime
     var resultCoord=beamLeg.travelPath.endPoint.loc
    if (stopTime<beamLeg.endTime) {
      for (linkId <- beamLeg.travelPath.linkIds) {
        val linkEndTime=currentTime + getTravelTimeEstimate(currentTime, linkId)
        breakable {
          if (stopTime < linkEndTime) {
              resultCoord=getLinkCoord(linkId)
            break
          }
        }
      }
    }
    */

    val pctTravelled=(stopTime-beamLeg.startTime)/(beamLeg.endTime-beamLeg.startTime)
    val directionCoordVector=getDirectionCoordVector(beamLeg.travelPath.startPoint.loc,beamLeg.travelPath.endPoint.loc)
    getCoord(beamLeg.travelPath.startPoint.loc,scaleDirectionVector(directionCoordVector,pctTravelled))
  }

  // TODO: move to some utility class,   e.g. geo
  private def getDirectionCoordVector(startCoord:Coord, endCoord:Coord): Coord ={
    new Coord(endCoord.getX()-startCoord.getX(),endCoord.getY()-startCoord.getY())
  }

  private def getCoord(startCoord:Coord,directionCoordVector:Coord): Coord ={
    new Coord(startCoord.getX()+directionCoordVector.getX(),startCoord.getY()+directionCoordVector.getY())
  }

  private def scaleDirectionVector(directionCoordVector:Coord, scalingFactor:Double):Coord={
    new Coord(directionCoordVector.getX()*scalingFactor,directionCoordVector.getY()*scalingFactor)
  }

}
