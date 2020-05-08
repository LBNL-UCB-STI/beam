package beam.router

import java.io.File

import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
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
  val location: Location = new Coord(-83.40673203295833, 42.41642536832911)
  val coord = new Coord()

  it should "be able to snap to R5 edge" in {
    val coord = geo.snapToR5Edge(
      transportNetwork.streetLayer,
      geo.utm2Wgs(location)
    )

    coord shouldBe equal(coord)
  }
}
