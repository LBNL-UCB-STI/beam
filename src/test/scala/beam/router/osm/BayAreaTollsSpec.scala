package beam.router.osm

import com.conveyal.r5.kryo.KryoNetworkSerializer
import com.conveyal.r5.profile.{ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets.StreetRouter
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig

import java.io.File

class BayAreaTollsSpec extends AnyWordSpecLike with Matchers {

  "Bay Area Toll Pricing" should {
    val tollPricesFile = "production/sfbay/toll-prices.csv"
    val config = testConfig("test/input/beamville/beam.conf")
      .withValue("beam.agentsim.toll.filePath", ConfigValueFactory.fromAnyRef(tollPricesFile))
      .resolve()
    val tollCalculator = new TollCalculator(BeamConfig(config))

    "correctly load and calculate tolls for all 8 Bay Area toll bridges" in {
      // 1. San Francisco-Oakland Bay Bridge (I-80): WB $7.00, EB free
      tollCalculator.calcTollByLinkId(109782, 36000) should be(7.00)
      tollCalculator.calcTollByLinkId(115966, 36000) should be(0.00)

      // 2. Golden Gate Bridge (US-101): SB $8.75, NB free
      tollCalculator.calcTollByLinkId(110154, 36000) should be(8.75)
      tollCalculator.calcTollByLinkId(104744, 36000) should be(0.00)

      // 3. San Mateo-Hayward Bridge (CA-92): WB $7.00, EB free
      tollCalculator.calcTollByLinkId(68116, 36000) should be(7.00)
      tollCalculator.calcTollByLinkId(70032, 36000) should be(0.00)

      // 4. Dumbarton Bridge (CA-84): WB $7.00, EB free
      tollCalculator.calcTollByLinkId(22342, 36000) should be(7.00)
      tollCalculator.calcTollByLinkId(67916, 36000) should be(0.00)

      // 5. Richmond-San Rafael Bridge (I-580): WB $7.00, EB free
      tollCalculator.calcTollByLinkId(93522, 36000) should be(7.00)
      tollCalculator.calcTollByLinkId(110062, 36000) should be(0.00)

      // 6. Carquinez Bridge (I-80): EB (vallejo-bound) $7.00, WB free
      tollCalculator.calcTollByLinkId(92356, 36000) should be(7.00)
      tollCalculator.calcTollByLinkId(96602, 36000) should be(0.00)

      // 7. Benicia-Martinez Bridge (I-680): NB (benicia-bound) $7.00, SB free
      tollCalculator.calcTollByLinkId(81518, 36000) should be(7.00)
      tollCalculator.calcTollByLinkId(96458, 36000) should be(0.00)

      // 8. Antioch Bridge (CA-160): NB (sacramento-bound) $7.00, SB free
      tollCalculator.calcTollByLinkId(60396, 36000) should be(7.00)
      tollCalculator.calcTollByLinkId(59068, 36000) should be(0.00)
    }

    "correctly evaluate route tolls between Oakland TAZ 968 and SF TAZ 13" in {
      val networkFile = new File("production/sfbay/r5/sfbay-cbg5500-weakConn-network/network.dat")
      val tn = KryoNetworkSerializer.read(networkFile)

      val utmToWgs84 = new GeotoolsTransformation("epsg:26910", "epsg:4326")
      val oakCoordWgs = utmToWgs84.transform(new Coord(564218.986, 4184356.206))
      val sfCoordWgs = utmToWgs84.transform(new Coord(552837.421, 4182733.090))

      def routeCar(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double): Double = {
        val router = new StreetRouter(tn.streetLayer)
        router.streetMode = StreetMode.CAR
        router.profileRequest = new ProfileRequest()
        router.profileRequest.fromLat = fromLat
        router.profileRequest.fromLon = fromLon
        router.profileRequest.toLat = toLat
        router.profileRequest.toLon = toLon
        router.setOrigin(fromLat, fromLon)
        val destSplit = tn.streetLayer.findSplit(toLat, toLon, 2000.0, StreetMode.CAR)
        router.route()
        val lastState = router.getState(destSplit)
        assert(lastState != null, s"Route could not be found from ($fromLat, $fromLon) to ($toLat, $toLon)")

        var toll = 0.0
        var s = lastState
        while (s != null && s.backEdge != -1) {
          toll += tollCalculator.calcTollByLinkId(s.backEdge, 36000)
          s = s.backState
        }
        toll
      }

      // Westbound: Oakland TAZ 968 -> SF TAZ 13 crosses Bay Bridge WB -> $7.00
      val wbToll = routeCar(oakCoordWgs.getY, oakCoordWgs.getX, sfCoordWgs.getY, sfCoordWgs.getX)
      wbToll should be(7.00)

      // Eastbound: SF TAZ 13 -> Oakland TAZ 968 crosses Bay Bridge EB -> $0.00
      val ebToll = routeCar(sfCoordWgs.getY, sfCoordWgs.getX, oakCoordWgs.getY, oakCoordWgs.getX)
      ebToll should be(0.00)
    }
  }
}
