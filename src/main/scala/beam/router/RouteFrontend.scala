package beam.router

import akka.actor.{Actor, Props}
import akka.routing.FromConfig
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.{R5RoutingWorker, R5RoutingWorker_v2}
import beam.sim.BeamServices
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.config.Config
import org.matsim.api.core.v01.network.Network
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.vehicles.Vehicles

class RouteFrontend(config: Config) extends Actor {
  // This router is used both with lookup and deploy of routees. If you
  // have a router with only lookup of routees you can use Props.empty
  // instead of Props[StatsWorker.class].
  val workerRouter = context.actorOf(FromConfig.props(Props(classOf[R5RoutingWorker_v2],config)),
    name = "workerRouter")

  def receive = {
    case other =>
      workerRouter ! other
  }
}
