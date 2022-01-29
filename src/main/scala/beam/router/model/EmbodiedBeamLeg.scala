package beam.router.model

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import com.conveyal.r5.streets.EdgeStore
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id

case class EmbodiedBeamLeg(
  beamLeg: BeamLeg,
  beamVehicleId: Id[BeamVehicle],
  beamVehicleTypeId: Id[BeamVehicleType],
  asDriver: Boolean,
  cost: Double,
  unbecomeDriverOnCompletion: Boolean,
  isPooledTrip: Boolean = false,
  replanningPenalty: Double = 0
) {
  val isRideHail: Boolean = BeamVehicle.isRidehailVehicle(beamVehicleId)
}

object EmbodiedBeamLeg {

  def dummyLegAt(
    start: Int,
    vehicleId: Id[BeamVehicle],
    isLastLeg: Boolean,
    location: Location,
    mode: BeamMode,
    vehicleTypeId: Id[BeamVehicleType],
    asDriver: Boolean = true,
    duration: Int = 0
  ): EmbodiedBeamLeg = {
    EmbodiedBeamLeg(
      BeamLeg.dummyLeg(start, location, mode, duration),
      vehicleId,
      vehicleTypeId,
      asDriver,
      0,
      unbecomeDriverOnCompletion = isLastLeg
    )
  }

  def makeLegsConsistent(legs: Vector[EmbodiedBeamLeg]): Vector[EmbodiedBeamLeg] = {
    var runningStartTime = legs.head.beamLeg.startTime
    for (leg <- legs) yield {
      val newLeg = leg.copy(beamLeg = leg.beamLeg.updateStartTime(runningStartTime))
      runningStartTime = newLeg.beamLeg.endTime
      newLeg
    }
  }

  def splitLegForParking(
    leg: EmbodiedBeamLeg,
    beamServices: BeamServices,
    transportNetwork: TransportNetwork
  ): Vector[EmbodiedBeamLeg] = {
    val theLinkIds = leg.beamLeg.travelPath.linkIds
    val indexFromEnd = Math.min(
      Math.max(
        theLinkIds.reverse
          .map(lengthOfLink(_, transportNetwork))
          .scanLeft(0.0)(_ + _)
          .indexWhere(
            _ > beamServices.beamConfig.beam.agentsim.thresholdForMakingParkingChoiceInMeters
          ),
        1
      ),
      theLinkIds.length - 1
    )
    val indexFromBeg = theLinkIds.length - indexFromEnd
    val firstTravelTimes = leg.beamLeg.travelPath.linkTravelTime.take(indexFromBeg)
    val secondPathLinkIds = theLinkIds.takeRight(indexFromEnd + 1)
    val secondTravelTimes = leg.beamLeg.travelPath.linkTravelTime.takeRight(indexFromEnd + 1)
    val secondDuration = Math.min(math.round(secondTravelTimes.tail.sum.toFloat), leg.beamLeg.duration)
    val firstDuration = leg.beamLeg.duration - secondDuration
    val secondDistance =
      Math.min(secondPathLinkIds.tail.map(lengthOfLink(_, transportNetwork)).sum, leg.beamLeg.travelPath.distanceInM)
    val firstPathEndpoint =
      SpaceTime(
        beamServices.geo
          .coordOfR5Edge(transportNetwork.streetLayer, theLinkIds(math.min(theLinkIds.size - 1, indexFromBeg))),
        leg.beamLeg.startTime + firstDuration
      )
    val secondPath = leg.beamLeg.travelPath.copy(
      linkIds = secondPathLinkIds,
      linkTravelTime = secondTravelTimes,
      startPoint = firstPathEndpoint,
      endPoint =
        leg.beamLeg.travelPath.endPoint.copy(time = (firstPathEndpoint.time + secondTravelTimes.tail.sum).toInt),
      distanceInM = secondDistance
    )
    val firstPath = leg.beamLeg.travelPath.copy(
      linkIds = theLinkIds.take(indexFromBeg),
      linkTravelTime = firstTravelTimes,
      endPoint =
        firstPathEndpoint.copy(time = (leg.beamLeg.travelPath.startPoint.time + firstTravelTimes.tail.sum).toInt),
      distanceInM = leg.beamLeg.travelPath.distanceInM - secondPath.distanceInM
    )
    val firstLeg = leg.copy(
      beamLeg = leg.beamLeg.copy(
        travelPath = firstPath,
        duration = firstDuration
      ),
      unbecomeDriverOnCompletion = false
    )
    val secondLeg = leg.copy(
      beamLeg = leg.beamLeg.copy(
        travelPath = secondPath,
        startTime = firstLeg.beamLeg.startTime + firstLeg.beamLeg.duration,
        duration = secondDuration
      ),
      cost = 0
    )
    assert((firstLeg.cost + secondLeg.cost).equals(leg.cost))
    assert(firstLeg.beamLeg.duration + secondLeg.beamLeg.duration == leg.beamLeg.duration)
    assert(
      Math.abs(
        leg.beamLeg.travelPath.distanceInM - firstLeg.beamLeg.travelPath.distanceInM - secondLeg.beamLeg.travelPath.distanceInM
      ) < 1.0
    )
    Vector(firstLeg, secondLeg)
  }

  def lengthOfLink(linkId: Int, transportNetwork: TransportNetwork): Double = {
    val edge: EdgeStore#Edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
    edge.getLengthM
  }
}
