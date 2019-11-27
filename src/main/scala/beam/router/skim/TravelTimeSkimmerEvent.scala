package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.TravelTimeSkimmer.{TTSkimmerInternal, TTSkimmerKey}
import beam.sim.BeamServices

case class TravelTimeSkimmerEvent(eventTime: Double, beamServices: BeamServices, carLeg: EmbodiedBeamLeg)
    extends AbstractSkimmerEvent(eventTime, beamServices) {
  override protected val skimName: String =
    beamServices.beamConfig.beam.router.skim.travel_time_skimmer.name
  override def getKey: AbstractSkimmerKey = key
  override def getSkimmerInternal: AbstractSkimmerInternal = skimInternal

  val (key, skimInternal) = observeTrip(carLeg)

  private def observeTrip(carLeg: EmbodiedBeamLeg): (TTSkimmerKey, TTSkimmerInternal) = {
    import beamServices._

    // In case of `CAV` we have to override its mode to `CAR`
    val fixedCarLeg = if (carLeg.beamLeg.mode == BeamMode.CAV) {
      carLeg.copy(beamLeg = carLeg.beamLeg.copy(mode = BeamMode.CAR))
    } else {
      carLeg
    }
    val carTrip = EmbodiedBeamTrip(Vector(fixedCarLeg))
    val origCoord = geo.wgs2Utm(carTrip.beamLegs.head.travelPath.startPoint.loc)
    val origTaz = beamScenario.tazTreeMap
      .getTAZ(origCoord.getX, origCoord.getY)
      .tazId
    val destCoord = geo.wgs2Utm(carTrip.beamLegs.last.travelPath.endPoint.loc)
    val destTaz = beamScenario.tazTreeMap
      .getTAZ(destCoord.getX, destCoord.getY)
      .tazId
    val hour = SkimsUtils.timeToBin(carTrip.beamLegs.head.startTime)
    val timeSimulated = carTrip.totalTravelTimeInSecs.toDouble

    val skimmerKey = TTSkimmerKey(origTaz, destTaz, hour)
    val skimmerInternal = TTSkimmerInternal(timeSimulated = timeSimulated, timeObserved = 0)
    (skimmerKey, skimmerInternal)
  }
}
