package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.ActivitySimSkimmer.{ActivitySimSkimmerInternal, ActivitySimSkimmerKey}
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

  private def calcTimes(trip: EmbodiedBeamTrip): (Double, Double, Double, Double) = {
    var walkAccess = 0
    var walkEgress = 0
    var walkAuxiliary = 0

    var sawNonWalkModes = 0
    var currentWalkTime = 0
    var totalInVehicleTime = 0

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
      }

      if (inVehicleModes.contains(leg.beamLeg.mode)) {
        totalInVehicleTime += leg.beamLeg.duration
      }
    }
    walkEgress = currentWalkTime
    (walkAccess, walkAuxiliary, walkEgress, totalInVehicleTime)
  }

  private def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ): (ActivitySimSkimmerKey, ActivitySimSkimmerInternal) = {
    val pathType = ActivitySimPathType.determineTripPathType(trip)
    val correctedTrip = ODSkimmerEvent.correctTrip(trip, trip.tripClassifier)
    val beamLegs = correctedTrip.beamLegs
    val origLeg = beamLegs.head
    val timeBin = SkimsUtils.timeToBin(origLeg.startTime)
    val distInMeters = beamLegs.map(_.travelPath.distanceInM).sum
    val (driveTimeInSeconds, driveDistanceInMeters) = beamLegs.foldLeft((0, 0.0)) {
      case ((duration, distanceInM), leg) =>
        leg.mode match {
          case BeamMode.CAV | BeamMode.CAR => (duration + leg.duration, distanceInM + leg.travelPath.distanceInM)
          case _                           => (duration, distanceInM)

        }
    }

    val key = ActivitySimSkimmerKey(timeBin, pathType, origin, destination)

    val (walkAccess, walkAuxiliary, walkEgress, totalInVehicleTime) = calcTimes(trip)

    val payload =
      ActivitySimSkimmerInternal(
        travelTimeInMinutes = correctedTrip.totalTravelTimeInSecs.toDouble / 60.0,
        generalizedTimeInMinutes = generalizedTimeInHours * 60,
        generalizedCost = generalizedCost,
        distanceInMeters = if (distInMeters > 0.0) { distInMeters } else { 1.0 },
        cost = correctedTrip.costEstimate,
        energy = energyConsumption,
        walkAccessInMinutes = walkAccess / 60.0,
        walkEgressInMinutes = walkEgress / 60.0,
        walkAuxiliaryInMinutes = walkAuxiliary / 60.0,
        totalInVehicleTimeInMinutes = totalInVehicleTime / 60.0,
        driveTimeInMinutes = driveTimeInSeconds / 60.0,
        driveDistanceInMeters = driveDistanceInMeters
      )
    (key, payload)
  }
}

object ActivitySimSkimmerEvent {

  val carModes: Set[BeamMode] = Set(BeamMode.CAV, BeamMode.CAR)
  val inVehicleModes: Set[BeamMode] = carModes ++ BeamMode.transitModes ++ BeamMode.massTransitModes
}
