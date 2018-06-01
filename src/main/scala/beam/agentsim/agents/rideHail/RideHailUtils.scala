package beam.agentsim.agents.rideHail

import beam.router.RoutingModel
import beam.router.RoutingModel.BeamLeg
import beam.sim.BeamServices
import beam.utils.DebugLib
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Coord, Id}

import scala.util.control.Breaks._

object RideHailUtils {

def getUpdatedBeamLegAfterStopDriving(originalBeamLeg: BeamLeg, stopTime:Double, transportNetwork: TransportNetwork, beamServices: BeamServices): BeamLeg = {

  //beamServices.geo.getNearestR5Edge(transportNetwork.streetLayer,currentLeg.travelPath.endPoint.loc,10000)
  if (stopTime >= originalBeamLeg.endTime || stopTime < originalBeamLeg.startTime) return null//throw new Exception("Stop Time should always fall in leg duration.") // TODO: make custom exception

  val pctTravelled = (stopTime - originalBeamLeg.startTime) / originalBeamLeg.duration
  val distanceOfNewPath = originalBeamLeg.travelPath.distanceInM * pctTravelled


  var updatedLinkIds: Vector[Int] = Vector(originalBeamLeg.travelPath.linkIds.head)
  val updatedEndPoint = originalBeamLeg.travelPath.endPoint
  val updatedDistanceInMeters = originalBeamLeg.travelPath.distanceInM

  var resultCoord = originalBeamLeg.travelPath.endPoint.loc

  var linkIds = updatedLinkIds
  if (stopTime < originalBeamLeg.endTime) {
    for (linkId <- originalBeamLeg.travelPath.linkIds.tail) {
      linkIds = linkIds :+ linkId
      val duration = getDuration(originalBeamLeg.updateLinks(linkIds))

      breakable {
        if (distanceOfNewPath < duration) {
          resultCoord = getR5EdgeCoord(linkId, transportNetwork)
          break
        } else {
          updatedLinkIds = linkIds
        }
      }
    }
  }

  def getDuration(leg: BeamLeg) = {
    val travelTime = (time: Long, linkId: Int) => {
      val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
      (edge.getLengthM / edge.calculateSpeed(new ProfileRequest, StreetMode.valueOf(originalBeamLeg.mode.r5Mode.get.left.get.toString))).toLong
    }

    RoutingModel.traverseStreetLeg(leg, Id.createVehicleId(1), travelTime).map(e => e.getTime).max - leg.startTime
  }


  val updatedTravelPath=originalBeamLeg.travelPath.copy(linkIds = updatedLinkIds,endPoint = updatedEndPoint,distanceInM = updatedDistanceInMeters)
  val updatedDuration=(stopTime-originalBeamLeg.startTime).toLong

  //transportNetwork.streetLayer.edgeStore.get .getCursor(linkId).get

  DebugLib.emptyFunctionForSettingBreakPoint()
  val newLeg = originalBeamLeg.copy(duration = updatedDuration,travelPath = updatedTravelPath)
  newLeg
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
