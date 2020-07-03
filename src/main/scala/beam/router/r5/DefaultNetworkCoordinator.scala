package beam.router.r5

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging

case class DefaultNetworkCoordinator(beamConfig: BeamConfig) extends LazyLogging with NetworkCoordinator
