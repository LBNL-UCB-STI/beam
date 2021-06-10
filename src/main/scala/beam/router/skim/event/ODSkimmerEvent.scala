package beam.router.skim.event

import beam.router.Modes
import beam.router.Modes.BeamMode.WALK
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.SkimsUtils
import beam.router.skim.core.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.router.skim.core.{AbstractSkimmerEvent, AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.sim.BeamServices

case class ODSkimmerEvent(
  origin: String,
  destination: String,
  eventTime: Double,
  trip: EmbodiedBeamTrip,
  generalizedTimeInHours: Double,
  generalizedCost: Double,
  energyConsumption: Double,
  override val skimName: String
) extends AbstractSkimmerEvent(eventTime) {
  override def getKey: AbstractSkimmerKey = key
  override def getSkimmerInternal: AbstractSkimmerInternal = skimInternal

  val (key, skimInternal) = observeTrip(trip, generalizedTimeInHours, generalizedCost, energyConsumption)

  private def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double,
    level4CavTravelTimeScalingFactor: Double = 1.0
  ): (ODSkimmerKey, ODSkimmerInternal) = {
    val mode = trip.tripClassifier
    val correctedTrip = ODSkimmerEvent.correctTrip(trip, mode)
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
        distanceInM = if (dist > 0.0) { dist } else { 1.0 },
        cost = correctedTrip.costEstimate,
        energy = energyConsumption,
        level4CavTravelTimeScalingFactor = level4CavTravelTimeScalingFactor
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
    energyConsumption: Double
  ): ODSkimmerEvent = {
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
    ODSkimmerEvent(
      origin = origTaz.toString,
      destination = destTaz.toString,
      eventTime = eventTime,
      trip = trip,
      generalizedTimeInHours = generalizedTimeInHours,
      generalizedCost = generalizedCost,
      energyConsumption = energyConsumption,
      skimName = beamConfig.beam.router.skim.origin_destination_skimmer.name
    )
  }
}
