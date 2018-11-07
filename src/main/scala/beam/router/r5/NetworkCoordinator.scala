package beam.router.r5

import beam.sim.config.BeamConfig
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Network


case class NetworkCoordinator(beamConfig: BeamConfig) extends LazyLogging with NetworkCoordinatorI {


  var transportNetwork: TransportNetwork = _
  var network: Network = _

  override def preprocessing(): Unit = ???

  override def postProcessing(): Unit = {
    convertFrequenciesToTrips()
  }
}
