package beam.agentsim.agents.rideHail

import beam.agentsim.events.SpaceTime
import beam.router.RoutingModel
import beam.router.RoutingModel.BeamLeg
import beam.utils.GeoUtils
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Coord, Id}

import scala.util.control.Breaks._

object RideHailUtils {

  def getUpdatedBeamLegAfterStopDriving(originalBeamLeg: BeamLeg, stopTime: Double, transportNetwork: TransportNetwork): BeamLeg = {

    if (stopTime < originalBeamLeg.startTime || stopTime >= originalBeamLeg.endTime) return originalBeamLeg

    val pctTravelled = (stopTime - originalBeamLeg.startTime) / originalBeamLeg.duration
    val distanceOfNewPath = originalBeamLeg.travelPath.distanceInM * pctTravelled

    var updatedLinkIds: Vector[Int] = Vector()

    var endPointLocation = originalBeamLeg.travelPath.endPoint.loc
    var updatedDistanceInMeters = distanceOfNewPath

    var linkIds = updatedLinkIds
    for (linkId <- originalBeamLeg.travelPath.linkIds) {
      linkIds = linkIds :+ linkId
      val distance = getDistance(linkIds, transportNetwork)

      breakable {
        if (distanceOfNewPath < distance) {
          break
        } else {
          endPointLocation = GeoUtils.getR5EdgeCoord(linkId, transportNetwork)
          updatedDistanceInMeters = distance
          updatedLinkIds = linkIds
        }
      }
    }

    val updatedEndPoint = SpaceTime(endPointLocation, stopTime.toLong)
    val updatedTravelPath = originalBeamLeg.travelPath.copy(linkIds = updatedLinkIds, endPoint = updatedEndPoint, distanceInM = updatedDistanceInMeters)
    val updatedDuration = (stopTime - originalBeamLeg.startTime).toLong

    originalBeamLeg.copy(duration = updatedDuration, travelPath = updatedTravelPath)
  }

  def getDistance(linkIds: Vector[Int], transportNetwork: TransportNetwork): Double = {
    linkIds.map(linkId => {
      val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
      edge.getLengthM
    }).sum
  }

  def getDuration(leg: BeamLeg, transportNetwork: TransportNetwork): Double = {
    val travelTime = (time: Long, linkId: Int) => {
      val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
      (edge.getLengthM / edge.calculateSpeed(new ProfileRequest, StreetMode.valueOf(leg.mode.r5Mode.get.left.get.toString))).toLong
    }

    RoutingModel.traverseStreetLeg(leg, Id.createVehicleId(1), travelTime).map(e => e.getTime).max - leg.startTime
  }

  private def getVehicleCoordinateForInterruptedLeg(beamLeg: BeamLeg, stopTime: Double): Coord = {
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

    val pctTravelled = (stopTime - beamLeg.startTime) / (beamLeg.endTime - beamLeg.startTime)
    val directionCoordVector = getDirectionCoordVector(beamLeg.travelPath.startPoint.loc, beamLeg.travelPath.endPoint.loc)
    getCoord(beamLeg.travelPath.startPoint.loc, scaleDirectionVector(directionCoordVector, pctTravelled))
  }

  // TODO: move to some utility class,   e.g. geo
  private def getDirectionCoordVector(startCoord: Coord, endCoord: Coord): Coord = {
    new Coord((endCoord getX()) - startCoord.getX, endCoord.getY - startCoord.getY)
  }

  private def getCoord(startCoord: Coord, directionCoordVector: Coord): Coord = {
    new Coord(startCoord.getX + directionCoordVector.getX, startCoord.getY + directionCoordVector.getY)
  }

  private def scaleDirectionVector(directionCoordVector: Coord, scalingFactor: Double): Coord = {
    new Coord(directionCoordVector.getX * scalingFactor, directionCoordVector.getY * scalingFactor)
  }

}
