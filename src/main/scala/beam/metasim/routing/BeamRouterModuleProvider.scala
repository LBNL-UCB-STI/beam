package beam.metasim.routing

import beam.playground.metasim.services.location.BeamRouter
import com.google.inject.{Inject, Provider}
import com.typesafe.config.Config
import org.matsim.core.router.RoutingModule

/**
  * Created by sfeygin on 2/7/17.
  */

object BeamRouterModuleProvider {
}

class BeamRouterModuleProvider @Inject()(config: Config) extends Provider[RoutingModule] {
  // XXXX: Get router params from config and use BeamRouterImpl (to be redefined?)
  override def get(): RoutingModule = {
    new BeamRouter()
  }
}
