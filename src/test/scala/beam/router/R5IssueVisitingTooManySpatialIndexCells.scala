package beam.router

import java.io.File

import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Coord
import org.scalatest.{FlatSpec, Matchers}

class R5IssueVisitingTooManySpatialIndexCells extends FlatSpec with Matchers {

  class GeoUtilsTestImpl(localCRSString: String) extends GeoUtils {
    override def localCRS: String = localCRSString
  }

  val geo = new GeoUtilsTestImpl("epsg:26910")
  val dir = new File("test/test-resources/R5-detroit-square")
  val transportNetwork: TransportNetwork = TransportNetwork.fromDirectory(dir)
  val streetLayer = transportNetwork.streetLayer

  // val location: Location = new Coord(-83.140673203295833, 42.378642536832911)
  val location: Location = new Coord(-83.40862361941298, 42.41622317907699)

  it should "be able to snap to R5 edge" in {
    val split = streetLayer.findSplit(location.getY, location.getX, 10000, StreetMode.WALK)
    require(split != null)
  }
}
