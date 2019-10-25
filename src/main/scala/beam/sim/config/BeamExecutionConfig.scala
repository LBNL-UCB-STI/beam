package beam.sim.config

import org.matsim.core.config.{Config => MatsimConfig}

case class BeamExecutionConfig(beamConfig: BeamConfig, matsimConfig: MatsimConfig, outputDirectory: String)
