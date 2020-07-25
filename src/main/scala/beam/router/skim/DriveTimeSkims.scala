package beam.router.skim

import beam.sim.config.BeamConfig

case class DriveTimeSkims(beamConfig: BeamConfig) extends AbstractSkimmerReadOnly(beamConfig) {
  override protected val skimTimeBin: Int = beamConfig.beam.router.skim.drive_time_skimmer.timeBin
}
