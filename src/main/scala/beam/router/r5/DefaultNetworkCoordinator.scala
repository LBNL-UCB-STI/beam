package beam.router.r5

import beam.sim.config.BeamConfig
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Network

case class DefaultNetworkCoordinator(beamConfig: BeamConfig) extends LazyLogging with NetworkCoordinator {
  override var transportNetwork: TransportNetwork = _
  override var network: Network = _
}
