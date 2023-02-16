package beam.integration.ridehail

import beam.agentsim.events.PathTraversalEvent
import beam.integration.{IntegrationSpecCommon, StartWithCustomConfig}
import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.Id
import org.matsim.vehicles.Vehicle
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class MultipleRideHailManagerSpec extends AnyWordSpecLike with Matchers with BeamHelper with IntegrationSpecCommon {

  "Running beam with multiple RideHailManagers" must {
    "produce PathTraversalEvents for vehicles of each RHM" in {
      val start = new StartWithCustomConfig(
        testConfig("test/input/beamville/beam-multiple-rhm.conf")
          .withValue("beam.outputs.events.fileOutputFormats", ConfigValueFactory.fromAnyRef("xml"))
          .resolve()
      )
      val events = start.events
      val rhPTE = events.collect {
        case pte: PathTraversalEvent if pte.vehicleId.toString.startsWith("rideHail") => pte
      }
      val groupedByFleetId = rhPTE.groupBy(pte => getFleetId(pte.vehicleId))
      groupedByFleetId.keySet shouldBe Set("Uber", "Lyft","Cruise")
    }
  }

  private def getFleetId(vehicleId: Id[Vehicle]): String = vehicleId.toString.split('@')(1)

}
