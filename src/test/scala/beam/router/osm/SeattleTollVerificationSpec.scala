package beam.router.osm

import java.io.File
import beam.agentsim.events.SpaceTime
import beam.router.model.BeamPath
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.r5.kryo.KryoNetworkSerializer
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.streets.StreetRouter
import org.scalatest.Ignore
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.collection.mutable.ArrayBuffer

@Ignore
class SeattleTollVerificationSpec extends AnyWordSpecLike with Matchers {

  private val tollCsvFile = new File("production/seattle/toll-prices.csv")
  private val networkDatFile = new File("production/seattle/r5/seattle-cbg120-ferry-weakConn-network/network.dat")

  private lazy val config = BeamConfig(
    testConfig("test/input/beamville/beam.conf")
      .withValue("beam.agentsim.toll.filePath", com.typesafe.config.ConfigValueFactory.fromAnyRef(tollCsvFile.getAbsolutePath))
      .resolve()
  )

  private lazy val tollCalculator = new TollCalculator(config)

  "Seattle toll-prices.csv" should {

    "correctly parse and evaluate tolls on SR-520 EB (edge 26040)" in {
      val linkId = 26040
      // Early morning off-peak (06:00 = 21600s)
      tollCalculator.calcTollByLinkId(linkId, 21600) should be(3.40 +- 0.001)
      // Morning peak (08:30 = 30600s)
      tollCalculator.calcTollByLinkId(linkId, 30600) should be(4.50 +- 0.001)
      // Midday off-peak (12:00 = 43200s)
      tollCalculator.calcTollByLinkId(linkId, 43200) should be(3.40 +- 0.001)
      // Evening peak (17:30 = 63000s)
      tollCalculator.calcTollByLinkId(linkId, 63000) should be(4.50 +- 0.001)
      // Evening off-peak (21:00 = 75600s)
      tollCalculator.calcTollByLinkId(linkId, 75600) should be(3.40 +- 0.001)
      // Next day post-midnight (01:00 = 90000s)
      tollCalculator.calcTollByLinkId(linkId, 90000) should be(3.40 +- 0.001)
    }

    "correctly parse and evaluate tolls on SR-520 WB (edge 54368)" in {
      val linkId = 54368
      tollCalculator.calcTollByLinkId(linkId, 21600) should be(3.40 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 30600) should be(4.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 43200) should be(3.40 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 63000) should be(4.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 75600) should be(3.40 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 90000) should be(3.40 +- 0.001)
    }

    "correctly parse and evaluate tolls on SR-99 Tunnel NB (edge 55486)" in {
      val linkId = 55486
      tollCalculator.calcTollByLinkId(linkId, 21600) should be(1.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 30600) should be(2.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 43200) should be(1.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 63000) should be(2.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 75600) should be(1.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 90000) should be(1.50 +- 0.001)
    }

    "correctly parse and evaluate tolls on SR-99 Tunnel SB (edge 52342)" in {
      val linkId = 52342
      tollCalculator.calcTollByLinkId(linkId, 21600) should be(1.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 30600) should be(2.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 43200) should be(1.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 63000) should be(2.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 75600) should be(1.50 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 90000) should be(1.50 +- 0.001)
    }

    "correctly evaluate flat toll on Tacoma Narrows EB (edge 62366)" in {
      val linkId = 62366
      tollCalculator.calcTollByLinkId(linkId, 21600) should be(5.25 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 30600) should be(5.25 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 43200) should be(5.25 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 63000) should be(5.25 +- 0.001)
      tollCalculator.calcTollByLinkId(linkId, 75600) should be(5.25 +- 0.001)
    }

    "evaluate 0.0 for untolled Tacoma Narrows WB (edge 62258) and unlisted links" in {
      tollCalculator.calcTollByLinkId(62258, 30600) should be(0.0)
      tollCalculator.calcTollByLinkId(12345, 30600) should be(0.0)
    }

    "evaluate BeamPath tolls properly using calcTollByLinkIds" in {
      val ebPathPeak = BeamPath(
        linkIds = Array(100, 26040, 200),
        linkTravelTime = Array(10.0, 60.0, 10.0),
        transitStops = None,
        startPoint = SpaceTime(0.0, 0.0, 30000), // 08:20 AM Peak
        endPoint = SpaceTime(0.0, 0.0, 30070),
        distanceInM = 3500.0
      )
      tollCalculator.calcTollByLinkIds(ebPathPeak) should be(4.50 +- 0.001)

      val ebPathOffPeak = BeamPath(
        linkIds = Array(100, 26040, 200),
        linkTravelTime = Array(10.0, 60.0, 10.0),
        transitStops = None,
        startPoint = SpaceTime(0.0, 0.0, 43200), // 12:00 PM Off-Peak
        endPoint = SpaceTime(0.0, 0.0, 43270),
        distanceInM = 3500.0
      )
      tollCalculator.calcTollByLinkIds(ebPathOffPeak) should be(3.40 +- 0.001)
    }

    "verify that car routing across Lake Washington on SR-520 traverses edge 26040 and incurs toll > 0.0" in {
      assume(networkDatFile.exists(), s"Seattle network.dat not found at ${networkDatFile.getPath}, skipping in CI")
      val transportNetwork = KryoNetworkSerializer.read(networkDatFile)

      // Route Eastbound: Montlake (Seattle) to Medina (Eastside)
      val routerEB = new StreetRouter(transportNetwork.streetLayer)
      routerEB.streetMode = StreetMode.CAR
      // Origin: Montlake near SR 520 interchange
      routerEB.setOrigin(47.6442, -122.3000)
      // Destination: Medina near 84th Ave NE
      routerEB.setDestination(47.6360, -122.2300)
      routerEB.route()

      val stateEB = routerEB.getState(47.6360, -122.2300)
      stateEB should not be null

      // Collect traversed edges
      val traversedEdgesEB = new ArrayBuffer[Int]()
      var curr = stateEB
      while (curr != null && curr.backState != null) {
        if (curr.backEdge >= 0) {
          traversedEdgesEB += curr.backEdge
        }
        curr = curr.backState
      }

      println(s"Car route EB Montlake -> Medina traversed ${traversedEdgesEB.size} edges.")
      println(s"Traversed edges contains SR-520 EB link 26040: ${traversedEdgesEB.contains(26040)}")
      traversedEdgesEB should contain(26040)

      // Calculate total toll at peak morning (30000s)
      val totalTollPeak = traversedEdgesEB.map(e => tollCalculator.calcTollByLinkId(e, 30000)).sum
      println(s"Total toll at peak morning: $$$totalTollPeak")
      totalTollPeak should be(4.50 +- 0.001)

      // Calculate total toll at off-peak midday (43200s)
      val totalTollOffPeak = traversedEdgesEB.map(e => tollCalculator.calcTollByLinkId(e, 43200)).sum
      println(s"Total toll at off-peak midday: $$$totalTollOffPeak")
      totalTollOffPeak should be(3.40 +- 0.001)

      // Route Westbound: Medina to Montlake
      val routerWB = new StreetRouter(transportNetwork.streetLayer)
      routerWB.streetMode = StreetMode.CAR
      routerWB.setOrigin(47.6360, -122.2300)
      routerWB.setDestination(47.6442, -122.3000)
      routerWB.route()

      val stateWB = routerWB.getState(47.6442, -122.3000)
      stateWB should not be null

      val traversedEdgesWB = new ArrayBuffer[Int]()
      curr = stateWB
      while (curr != null && curr.backState != null) {
        if (curr.backEdge >= 0) {
          traversedEdgesWB += curr.backEdge
        }
        curr = curr.backState
      }

      println(s"Car route WB Medina -> Montlake traversed ${traversedEdgesWB.size} edges.")
      println(s"Traversed edges contains SR-520 WB link 54368: ${traversedEdgesWB.contains(54368)}")
      traversedEdgesWB should contain(54368)

      val totalTollWBEvening = traversedEdgesWB.map(e => tollCalculator.calcTollByLinkId(e, 60000)).sum
      println(s"Total toll WB at evening peak: $$$totalTollWBEvening")
      totalTollWBEvening should be(4.50 +- 0.001)
    }
  }
}
