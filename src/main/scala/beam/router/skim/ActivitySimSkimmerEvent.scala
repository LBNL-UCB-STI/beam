package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.{ActivitySimSkimmerInternal, ActivitySimSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
import com.typesafe.scalalogging.LazyLogging

case class ActivitySimSkimmerEvent(
  origin: String,
  destination: String,
  eventTime: Double,
  trip: EmbodiedBeamTrip,
  generalizedTimeInHours: Double,
  generalizedCost: Double,
  energyConsumption: Double,
  override val skimName: String
) extends AbstractSkimmerEvent(eventTime)
    with LazyLogging {

  import ActivitySimSkimmerEvent._

  override def getKey: AbstractSkimmerKey = key
  override def getSkimmerInternal: AbstractSkimmerInternal = skimInternal

  val (key, skimInternal) = observeTrip(trip, generalizedTimeInHours, generalizedCost, energyConsumption)

  private def calcTimes(trip: EmbodiedBeamTrip): (Double, Double, Double, Double, Double, Double, Int) = {
    var walkAccess = 0
    var walkEgress = 0
    var walkAuxiliary = 0

    var sawNonWalkModes = 0
    var currentWalkTime = 0
    var totalInVehicleTime = 0
    var initialWaitTime = 0

    var previousLegEndTime = 0

    var travelingInTransit = false
    var numberOfTransitTrips = 0

    trip.legs.foreach { leg =>
      if (leg.beamLeg.mode == BeamMode.WALK) {
        currentWalkTime += leg.beamLeg.duration
      } else {
        sawNonWalkModes += 1
        if (sawNonWalkModes == 1) {
          walkAccess = currentWalkTime
        } else {
          walkAuxiliary += currentWalkTime
        }
        currentWalkTime = 0

        if (inVehicleModes.contains(leg.beamLeg.mode)) {
          totalInVehicleTime += leg.beamLeg.duration
        }
      }

      // TODO: This misses some cases where we don't have a walk leg between transit legs
      if (transitModes.contains(leg.beamLeg.mode)) {
        if (!travelingInTransit) {
          travelingInTransit = true
          if (numberOfTransitTrips == 0) { initialWaitTime = leg.beamLeg.startTime - previousLegEndTime }
          numberOfTransitTrips += 1
        }
      } else {
        travelingInTransit = false
      }
      previousLegEndTime = leg.beamLeg.endTime
    }
    val transferWaitTime = if (numberOfTransitTrips > 0) {
      trip.totalTravelTimeInSecs - trip.legs.foldLeft(0)((tot, leg) => tot + leg.beamLeg.duration) - initialWaitTime
    } else { 0 }

    walkEgress = currentWalkTime
    (
      walkAccess,
      walkAuxiliary,
      walkEgress,
      totalInVehicleTime,
      initialWaitTime,
      transferWaitTime,
      numberOfTransitTrips
    )
  }

  private def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ): (ActivitySimSkimmerKey, ActivitySimSkimmerInternal) = {
    val pathType = ActivitySimPathType.determineTripPathType(trip)
    val beamLegs = trip.beamLegs
    val origLeg = beamLegs.head
    val timeBin = SkimsUtils.timeToBin(origLeg.startTime)
    val distInMeters = beamLegs.map(_.travelPath.distanceInM).sum
    val (driveTimeInSeconds, driveDistanceInMeters, ferryTimeInSeconds, keyInVehicleTimeInSeconds) =
      beamLegs.foldLeft((0, 0.0, 0, 0)) { case ((driveTime, driveDistanceInM, ferryTime, keyTime), leg) =>
        leg.mode match {
          case BeamMode.CAV | BeamMode.CAR =>
            (driveTime + leg.duration, driveDistanceInM + leg.travelPath.distanceInM, ferryTime, keyTime)
          case BeamMode.FERRY if toKeyMode(pathType).contains(BeamMode.TRAM) =>
            (
              driveTime,
              driveDistanceInM,
              ferryTime + leg.duration,
              keyTime + leg.duration
            ) // This is funky b/c light rail (a.k.a. tram) and ferry are grouped together in ASim modes
          case BeamMode.FERRY => (driveTime, driveDistanceInM, ferryTime + leg.duration, keyTime)
          case legMode if toKeyMode(pathType).contains(legMode) =>
            (driveTime, driveDistanceInM, ferryTime, keyTime + leg.duration)
          case _ => (driveTime, driveDistanceInM, ferryTime, keyTime)
        }
      }

    val key = ActivitySimSkimmerKey(timeBin, pathType, origin, destination)

    val (walkAccess, walkAuxiliary, walkEgress, totalInVehicleTime, waitInitial, waitAuxiliary, numberOfTransitTrips) =
      calcTimes(trip)

    val payload =
      ActivitySimSkimmerInternal(
        travelTimeInMinutes = pathType match {
          case SOV | HOV2 | HOV3 | SOVTOLL | HOV2TOLL | HOV3TOLL => totalInVehicleTime / 60.0
          case _                                                 => trip.totalTravelTimeInSecs.toDouble / 60.0
        },
        generalizedTimeInMinutes = generalizedTimeInHours * 60,
        generalizedCost = generalizedCost,
        distanceInMeters = {
          pathType match {
            case SOV | HOV2 | HOV3 | SOVTOLL | HOV2TOLL | HOV3TOLL => driveDistanceInMeters
            case _                                                 => distInMeters
          }
        } max 1.0,
        cost = trip.costEstimate,
        energy = energyConsumption,
        walkAccessInMinutes = walkAccess / 60.0,
        walkEgressInMinutes = walkEgress / 60.0,
        walkAuxiliaryInMinutes = walkAuxiliary / 60.0,
        waitInitialInMinutes = waitInitial / 60.0,
        waitAuxiliaryInMinutes = waitAuxiliary / 60.0,
        totalInVehicleTimeInMinutes = totalInVehicleTime / 60.0,
        driveTimeInMinutes = driveTimeInSeconds / 60.0,
        driveDistanceInMeters = driveDistanceInMeters,
        ferryInVehicleTimeInMinutes = ferryTimeInSeconds / 60.0,
        keyInVehicleTimeInMinutes = keyInVehicleTimeInSeconds / 60.0,
        transitBoardingsCount = numberOfTransitTrips,
        failedTrips = 0,
        observations = 1
      )
    (key, payload)
  }
}

case class ActivitySimSkimmerFailedTripEvent(
  origin: String,
  destination: String,
  eventTime: Double,
  activitySimPathType: ActivitySimPathType,
  iterationNumber: Int,
  override val skimName: String
) extends AbstractSkimmerEvent(eventTime) {

  override def getKey: ActivitySimSkimmerKey =
    ActivitySimSkimmerKey(SkimsUtils.timeToBin(Math.round(eventTime).toInt), activitySimPathType, origin, destination)

  override def getSkimmerInternal: ActivitySimSkimmerInternal = {
    ActivitySimSkimmerInternal(
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      failedTrips = 1,
      observations = 0
    )
  }
}

object ActivitySimSkimmerEvent {

  val carModes: Set[BeamMode] = Set(BeamMode.CAV, BeamMode.CAR, BeamMode.RIDE_HAIL, BeamMode.RIDE_HAIL_POOLED)
  val transitModes: Set[BeamMode] = (BeamMode.transitModes ++ BeamMode.massTransitModes).toSet
  val inVehicleModes: Set[BeamMode] = carModes ++ transitModes
}
