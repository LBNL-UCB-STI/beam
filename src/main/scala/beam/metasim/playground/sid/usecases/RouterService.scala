package beam.metasim.playground.sid.usecases

import java.util

import com.google.inject.Inject
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}

/**
  * Created by sfeygin on 2/6/17.
  */
trait RouterService{
  def getRoute(startLink:Id[Link],endLink:Id[Link]):String ={"Hi"}
}

class RouterServiceI @Inject()(network: Network) extends RouterService{
  val links: util.Map[Id[Link], _ <: Link] = network.getLinks

  override def getRoute(startLink: Id[Link], endLink:Id[Link]): String ={
    network.toString
  }

}
