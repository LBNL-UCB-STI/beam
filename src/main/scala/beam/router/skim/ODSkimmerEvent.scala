package beam.router.skim

import beam.router.Modes.BeamMode.WALK
import beam.router.model.EmbodiedBeamTrip
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.sim.BeamServices

case class ODSkimmerEvent(
  eventTime: Double,
  beamServices: BeamServices,
  trip: EmbodiedBeamTrip,
  generalizedTimeInHours: Double,
  generalizedCost: Double,
  energyConsumption: Double
) extends SkimmerEvent(eventTime, beamServices) {
  override def getEventType: String = "ODSkimmerEvent"
  override def getKey: AbstractSkimmerKey = key
  override def getSkimmerInternal: AbstractSkimmerInternal = skimInternal

  val (key, skimInternal) = observeTrip(trip, generalizedTimeInHours, generalizedCost, energyConsumption)

  private def observeTrip(
    trip: EmbodiedBeamTrip,
    generalizedTimeInHours: Double,
    generalizedCost: Double,
    energyConsumption: Double
  ) = {
    import beamServices._
    val mode = trip.tripClassifier
    val correctedTrip = mode match {
      case WALK =>
        trip
      case _ =>
        val legs = trip.legs.drop(1).dropRight(1)
        EmbodiedBeamTrip(legs)
    }
    val beamLegs = correctedTrip.beamLegs
    val origLeg = beamLegs.head
    val origCoord = geo.wgs2Utm(origLeg.travelPath.startPoint.loc)
    val origTaz = beamScenario.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destLeg = beamLegs.last
    val destCoord = geo.wgs2Utm(destLeg.travelPath.endPoint.loc)
    val destTaz = beamScenario.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val timeBin = ODSkimmerUtils.timeToBin(origLeg.startTime)
    val dist = beamLegs.map(_.travelPath.distanceInM).sum
    val key = ODSkimmerKey(timeBin, mode, origTaz, destTaz)
    val payload =
      ODSkimmerInternal(
        correctedTrip.totalTravelTimeInSecs.toDouble,
        generalizedTimeInHours * 3600,
        generalizedCost,
        if (dist > 0.0) { dist } else { 1.0 },
        correctedTrip.costEstimate,
        1,
        energyConsumption
      )
    (key, payload)
  }
}
