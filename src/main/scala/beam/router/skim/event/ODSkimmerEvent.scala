package beam.router.skim.event

import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.WALK
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.SkimsUtils
import beam.router.skim.core.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey, ODSkimmer}
import beam.sim.BeamServices
import org.matsim.api.core.v01.Coord

case class ODSkimmerEvent(
  origin: String,
  destination: String,
  eventTime: Double,
  trip: EmbodiedBeamTrip,
  generalizedTimeInHours: Double,
  generalizedCost: Double,
  energyConsumption: Double,
  maybePayloadWeightInKg: Option[Double],
  failedTrip: Boolean,
  override val skimName: String
) extends AbstractSkimmerEvent(eventTime) {
  override def getKey: AbstractSkimmerKey = key
  override def getSkimmerInternal: AbstractSkimmerInternal = skimInternal

  val (key, skimInternal) =
    observeTrip(trip, generalizedTimeInHours, generalizedCost, energyConsumption, maybePayloadWeightInKg)

  private def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double,
    maybePayloadWeightInKg: Option[Double],
    level4CavTravelTimeScalingFactor: Double = 1.0
  ): (ODSkimmerKey, ODSkimmerInternal) = {
    val mode = if (maybePayloadWeightInKg.isDefined) BeamMode.FREIGHT else trip.tripClassifier
    val correctedTrip = ODSkimmerEvent.correctTrip(trip, trip.tripClassifier)
    val beamLegs = correctedTrip.beamLegs
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val origLeg = beamLegs.head
    val timeBin = SkimsUtils.timeToBin(origLeg.startTime)
    val dist = beamLegs.map(_.travelPath.distanceInM).sum
    val key = ODSkimmerKey(timeBin, mode, origin, destination)
    val payload =
      ODSkimmerInternal(
        travelTimeInS = correctedTrip.totalTravelTimeInSecs.toDouble,
        generalizedTimeInS = generalizedTimeInHours * 3600,
        generalizedCost = generalizedCost,
        distanceInM = if (dist > 0.0) { dist }
        else { 1.0 },
        cost = correctedTrip.costEstimate,
        payloadWeightInKg = maybePayloadWeightInKg.getOrElse(0.0),
        energy = energyConsumption,
        level4CavTravelTimeScalingFactor = level4CavTravelTimeScalingFactor,
        failedTrips = if (failedTrip) 1 else 0
      )
    (key, payload)
  }
}

object ODSkimmerEvent {

  def correctTrip(trip: EmbodiedBeamTrip, mode: Modes.BeamMode): EmbodiedBeamTrip = {
    val correctedTrip = mode match {
      case WALK =>
        trip
      case _ =>
        val legs = trip.legs.drop(1).dropRight(1)
        EmbodiedBeamTrip(legs)
    }
    correctedTrip
  }

  def forTaz(
    eventTime: Double,
    beamServices: BeamServices,
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    maybePayloadWeightInKg: Option[Double],
    energyConsumption: Double,
    failedTrip: Boolean
  ): (ODSkimmerEvent, Coord, Coord) = {
    import beamServices._
    val beamLegs = ODSkimmerEvent.correctTrip(trip, trip.tripClassifier).beamLegs
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val origLeg = beamLegs.head
    val origCoord = geo.wgs2Utm(origLeg.travelPath.startPoint.loc)
    val origTaz = beamScenario.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val destLeg = beamLegs.last
    val destCoord = geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = beamScenario.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    (
      ODSkimmerEvent(
        origin = origTaz.toString,
        destination = destTaz.toString,
        eventTime = eventTime,
        trip = trip,
        generalizedTimeInHours = generalizedTimeInHours,
        generalizedCost = generalizedCost,
        energyConsumption = energyConsumption,
        maybePayloadWeightInKg = maybePayloadWeightInKg,
        failedTrip = failedTrip,
        skimName = beamConfig.beam.router.skim.origin_destination_skimmer.name
      ),
      origCoord,
      destCoord
    )
  }
}

case class ODSkimmerFailedTripEvent(
  origin: String,
  destination: String,
  eventTime: Double,
  mode: BeamMode,
  iterationNumber: Int,
  override val skimName: String
) extends AbstractSkimmerEvent(eventTime) {

  override def getKey: AbstractSkimmerKey =
    ODSkimmerKey(SkimsUtils.timeToBin(Math.round(eventTime).toInt), mode, origin, destination)

  override def getSkimmerInternal: AbstractSkimmerInternal = {
    ODSkimmerInternal(
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      0.0,
      1.0,
      failedTrips = 1,
      observations = 0
    )
  }
}
