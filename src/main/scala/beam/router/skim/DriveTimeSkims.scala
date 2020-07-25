package beam.router.skim

import beam.sim.config.BeamConfig

case class DriveTimeSkims(beamConfig: BeamConfig) extends AbstractSkimmerReadOnly(beamConfig) {
  override def timeIntervalInSeconds: Int = beamConfig.beam.router.skim.drive_time_skimmer.timeBin
}
