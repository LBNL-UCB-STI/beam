package beam.agentsim.agents.ridehail

import beam.agentsim.events.SpaceTime
import beam.router.model.{BeamLeg, RoutingModel}
import beam.sim.common.GeoUtils
import com.conveyal.r5.profile.{ProfileRequest, StreetMode}
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks._

object RideHailUtils {

  def getUpdatedBeamLegAfterStopDriving(
    originalBeamLeg: BeamLeg,
    stopTime: Int,
    transportNetwork: TransportNetwork
  ): BeamLeg = {

    if (stopTime < originalBeamLeg.startTime || stopTime >= originalBeamLeg.endTime) {
      originalBeamLeg
    } else {

      val duration = stopTime - originalBeamLeg.startTime

      val pctTravelled: Double = duration / originalBeamLeg.duration

      val distanceOfNewPath = originalBeamLeg.travelPath.distanceInM * pctTravelled

      var endPointLocation: Coord = originalBeamLeg.travelPath.endPoint.loc

      var updatedDistanceInMeters: Double = distanceOfNewPath

      val linkIds = ListBuffer[Int]()

      var distance: Double = 0D

      for (linkId: Int <- originalBeamLeg.travelPath.linkIds) {
        linkIds += linkId
        distance += transportNetwork.streetLayer.edgeStore.getCursor(linkId).getLengthM

        breakable {
          if (distanceOfNewPath < distance) {
            break
          } else {
            endPointLocation = GeoUtils.getR5EdgeCoord(linkId, transportNetwork)
            updatedDistanceInMeters = distance
          }
        }
      }

      val updatedEndPoint = SpaceTime(endPointLocation, stopTime)

      val updatedTravelPath = originalBeamLeg.travelPath.copy(
        linkIds = linkIds.toVector,
        endPoint = updatedEndPoint,
        distanceInM = updatedDistanceInMeters
      )

      originalBeamLeg.copy(duration = duration, travelPath = updatedTravelPath)
    }
  }

  def getDuration(leg: BeamLeg, transportNetwork: TransportNetwork): Double = {
    val travelTime = (_: Int, linkId: Int) => {
      val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
      (edge.getLengthM / edge.calculateSpeed(
        new ProfileRequest,
        StreetMode.valueOf(leg.mode.r5Mode.get.left.get.toString)
      )).toInt
    }

    RoutingModel
      .traverseStreetLeg(leg, Id.createVehicleId(1), travelTime)
      .map(e => e.getTime)
      .max - leg.startTime
  }

  def getVehicleCoordinateForInterruptedLeg(beamLeg: BeamLeg, stopTime: Double): Coord = {
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
    val directionCoordVector =
      getDirectionCoordVector(beamLeg.travelPath.startPoint.loc, beamLeg.travelPath.endPoint.loc)
    getCoord(
      beamLeg.travelPath.startPoint.loc,
      scaleDirectionVector(directionCoordVector, pctTravelled)
    )
  }

  // TODO: move to some utility class,   e.g. geo
  private def getDirectionCoordVector(startCoord: Coord, endCoord: Coord): Coord = {
    new Coord((endCoord getX ()) - startCoord.getX, endCoord.getY - startCoord.getY)
  }

  private def getCoord(startCoord: Coord, directionCoordVector: Coord): Coord = {
    new Coord(
      startCoord.getX + directionCoordVector.getX,
      startCoord.getY + directionCoordVector.getY
    )
  }

  private def scaleDirectionVector(directionCoordVector: Coord, scalingFactor: Double): Coord = {
    new Coord(directionCoordVector.getX * scalingFactor, directionCoordVector.getY * scalingFactor)
  }

}
