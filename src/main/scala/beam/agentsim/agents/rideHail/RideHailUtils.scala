package beam.agentsim.agents.rideHail

import beam.router.RoutingModel.BeamLeg
import beam.sim.BeamServices
import beam.utils.DebugLib
import com.conveyal.r5.streets.EdgeStore
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Coord

import scala.util.control.Breaks._

object RideHailUtils {

def getUpdatedBeamLegAfterStopDriving(originalBeamLeg: BeamLeg, stopTime:Double, transportNetwork: TransportNetwork, beamServices: BeamServices): BeamLeg = {

  //beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer,currentLeg.travelPath.endPoint.loc,10000)

  val pctTravelled=(stopTime-originalBeamLeg.startTime)/(originalBeamLeg.endTime-originalBeamLeg.startTime)
  val distanceOfNewPath= originalBeamLeg.travelPath.distanceInM*pctTravelled


val updatedLinkIds=originalBeamLeg.travelPath.linkIds
  val updatedEndPoint=originalBeamLeg.travelPath.endPoint
  val updatedDistanceInMeters=originalBeamLeg.travelPath.distanceInM

  var distanceTravelled=0
  var resultCoord=originalBeamLeg.travelPath.endPoint.loc
  if (stopTime<originalBeamLeg.endTime) {
    for (linkId <- originalBeamLeg.travelPath.linkIds) {
      val linkEndTime=0//currentTime + getTravelTimeEstimate(currentTime, linkId)
      breakable {
        if (stopTime < linkEndTime) {
          resultCoord=getR5EdgeCoord(linkId,transportNetwork)
          break
        }
      }
    }
  }


  val updatedTravelPath=originalBeamLeg.travelPath.copy(linkIds = updatedLinkIds,endPoint = updatedEndPoint,distanceInM = updatedDistanceInMeters)
  val updatedDuration=(stopTime-originalBeamLeg.startTime).toLong

  //transportNetwork.streetLayer.edgeStore.get .getCursor(linkId).get

  DebugLib.emptyFunctionForSettingBreakPoint()
  originalBeamLeg.copy(duration = updatedDuration,travelPath = updatedTravelPath)
}

  // TODO: move to geoutils?
  private def getR5EdgeCoord(linkIdInt: Int, transportNetwork: TransportNetwork): Coord ={
    val  currentEdge  = transportNetwork.streetLayer.edgeStore.getCursor(linkIdInt)
    new Coord(currentEdge.getGeometry.getCoordinate.x, currentEdge.getGeometry.getCoordinate.y)
  }

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
