package beam.router.skim

import beam.router.Modes.BeamMode
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.DriveTimeSkimmer.{DriveTimeSkimmerInternal, DriveTimeSkimmerKey}
import beam.sim.BeamServices

case class DriveTimeSkimmerEvent(eventTime: Double, beamServices: BeamServices, carLeg: EmbodiedBeamLeg)
    extends AbstractSkimmerEvent(eventTime) {
  override protected val skimName: String =
    beamServices.beamConfig.beam.router.skim.drive_time_skimmer.name
  override def getKey: AbstractSkimmerKey = key
  override def getSkimmerInternal: AbstractSkimmerInternal = skimInternal

  val (key, skimInternal) = observeTrip(carLeg)

  private def observeTrip(carLeg: EmbodiedBeamLeg): (DriveTimeSkimmerKey, DriveTimeSkimmerInternal) = {
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

    val skimmerKey = DriveTimeSkimmerKey(origTaz, destTaz, hour)
    val skimmerInternal = DriveTimeSkimmerInternal(
      timeSimulated = timeSimulated,
      timeObserved = 0,
      observations = 1,
      iterations = beamServices.matsimServices.getIterationNumber + 1
    )
    (skimmerKey, skimmerInternal)
  }
}
