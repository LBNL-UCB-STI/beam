package beam.router.skim

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.RIDE_HAIL_TRANSIT
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.ActivitySimPathType._
import beam.router.skim.ActivitySimSkimmer.{ActivitySimSkimmerInternal, ActivitySimSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.util.Try

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

  val (key, skimInternal) = observeTrip(trip, energyConsumption)

  private def calcTimes(trip: EmbodiedBeamTrip): (Double, Double, Double, Double, Double, Double, Int, Int) = {
    var walkAccess = 0
    var walkEgress = 0
    var walkAuxiliary = 0

    var sawNonWalkModes = 0
    var currentWalkTime = 0
    var totalInVehicleTime = 0
    var initialWaitTime = 0

    var previousLegEndTime = 0

    var currentBeamVehicleId: Option[Id[BeamVehicle]] = None
    var numberOfTransitTrips = 0
    var numberOfTNCtrips = 0

    trip.legs.foreach { leg =>
      if (leg.beamLeg.mode == BeamMode.WALK) {
        currentWalkTime += leg.beamLeg.duration
      } else {
        sawNonWalkModes += 1
        if (sawNonWalkModes == 1) {
          walkAccess = currentWalkTime
          if (leg.isRideHail | transitModes.contains(leg.beamLeg.mode)) {
            initialWaitTime = leg.beamLeg.startTime - previousLegEndTime
          }
        } else {
          walkAuxiliary += currentWalkTime
        }
        currentWalkTime = 0

        if (leg.isRideHail & !currentBeamVehicleId.exists(_.equals(leg.beamVehicleId))) {
          numberOfTNCtrips += 1
        }

        if (inVehicleModes.contains(leg.beamLeg.mode)) {
          totalInVehicleTime += leg.beamLeg.duration
        }
      }

      if (transitModes.contains(leg.beamLeg.mode) & !currentBeamVehicleId.exists(_.equals(leg.beamVehicleId))) {
        numberOfTransitTrips += 1
      }

      previousLegEndTime = leg.beamLeg.endTime
      currentBeamVehicleId = Some(leg.beamVehicleId)
    }
    val transferWaitTime = if (numberOfTransitTrips > 0) {
      Try(trip.legs.last.beamLeg.endTime - trip.legs(1).beamLeg.startTime)
        .getOrElse(0) - trip.legs.tail.map(_.beamLeg.duration).sum
    } else { 0 }

    walkEgress = currentWalkTime
    (
      walkAccess,
      walkAuxiliary,
      walkEgress,
      totalInVehicleTime,
      initialWaitTime,
      transferWaitTime,
      numberOfTransitTrips,
      numberOfTNCtrips
    )
  }

  private def observeTrip(trip: EmbodiedBeamTrip, energyConsumption: Double) = {
    val (pathType, fleet) = ActivitySimPathType.determineTripPathTypeAndFleet(trip)
    if (walkTransitPathTypes.contains(pathType) & fleet.nonEmpty) {
      logger.warn(
        s"Why are we missing a car leg in a TNC transit trip?  Leg vehicles: ${trip.legs.map(_.beamVehicleId)}"
      )
    } else if (tncTransitPathTypes.contains(pathType) & fleet.isEmpty) {
      logger.warn(
        s"Why are we missing a TNC fleet for a TNC transit trip? Leg vehicles: ${trip.legs.map(_.beamVehicleId)}"
      )
    }
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

    val key = ActivitySimSkimmerKey(timeBin, pathType, origin, destination, fleet)

    val (
      walkAccess,
      walkAuxiliary,
      walkEgress,
      totalInVehicleTime,
      waitInitial,
      waitAuxiliary,
      numberOfTransitTrips,
      numberOfTncTrips
    ) =
      calcTimes(trip)

    val payload = {
      if (isTransit(pathType) & ((totalInVehicleTime <= 0) || (keyInVehicleTimeInSeconds <= 0))) {
        logger.warn(
          "Observed an activitySimSkimmerEvent for path type {}, but found that IVT was zero. Event {}",
          pathType.toString,
          trip.toString
        )
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
          failedTrips = 1,
          observations = 0
        )
      } else {
        ActivitySimSkimmerInternal(
          travelTimeInMinutes = pathType match {
            case SOV | HOV2 | HOV3 | SOVTOLL | HOV2TOLL | HOV3TOLL => totalInVehicleTime / 60.0
            case _                                                 => trip.totalTravelTimeInSecs.toDouble / 60.0
          },
          distanceInMeters = {
            pathType match {
              case SOV | HOV2 | HOV3 | SOVTOLL | HOV2TOLL | HOV3TOLL => driveDistanceInMeters
              case _                                                 => distInMeters
            }
          } max 1.0,
          costInDollars = pathType match {
            case TNC_SINGLE_TRANSIT | TNC_SHARED_TRANSIT =>
              trip.legs.collect { case l if !l.isRideHail => l.cost }.sum max 2.0
            case pt if ActivitySimPathType.transitPathTypes.contains(pt) => trip.costEstimate max 2.0
            case _                                                       => trip.costEstimate
          },
          energy = energyConsumption,
          walkAccessInMinutes = walkAccess / 60.0,
          walkEgressInMinutes = walkEgress / 60.0,
          walkAuxiliaryInMinutes = walkAuxiliary / 60.0,
          waitInitialInMinutes = waitInitial / 60.0,
          waitAuxiliaryInMinutes = waitAuxiliary / 60.0 max 0.0,
          totalInVehicleTimeInMinutes = totalInVehicleTime / 60.0,
          driveTimeInMinutes = driveTimeInSeconds / 60.0,
          driveDistanceInMeters = driveDistanceInMeters,
          ferryInVehicleTimeInMinutes = ferryTimeInSeconds / 60.0,
          keyInVehicleTimeInMinutes = keyInVehicleTimeInSeconds / 60.0,
          transitBoardingsCount = numberOfTransitTrips,
          tncBoardingsCount = numberOfTncTrips,
          failedTrips = 0,
          observations = 1
        )
      }
    }
    (key, payload)
  }
}

case class ActivitySimSkimmerFailedTripEvent(
  origin: String,
  destination: String,
  eventTime: Double,
  activitySimPathType: ActivitySimPathType,
  fleet: Option[String],
  iterationNumber: Int,
  override val skimName: String
) extends AbstractSkimmerEvent(eventTime) {

  override def getKey: ActivitySimSkimmerKey =
    ActivitySimSkimmerKey(
      SkimsUtils.timeToBin(Math.round(eventTime).toInt),
      activitySimPathType,
      origin,
      destination,
      fleet
    )

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
