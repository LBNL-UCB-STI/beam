package beam.router.skim

import beam.router.skim.core.AbstractSkimmerReadOnly
import beam.sim.BeamScenario
import beam.sim.config.BeamConfig

case class ActivitySimSkims(beamConfig: BeamConfig, beamScenario: BeamScenario) extends AbstractSkimmerReadOnly
