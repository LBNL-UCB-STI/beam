package beam.router

import beam.agentsim.agents.TransitVehicleInitializer
import beam.integration.IntegrationSpecCommon
import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig
import beam.utils.BeamVehicleUtils.readBeamVehicleTypeFile
import com.conveyal.r5.transit.RouteInfo
import com.typesafe.config.ConfigValueFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TransitVehicleInitializerSpec extends AnyWordSpecLike with Matchers with IntegrationSpecCommon {
  "getVehicleType" should {
    val transitInitializer: TransitVehicleInitializer = init

    "return SUV, based on agency[217] and route[1342] map" in {
      val expectedType = "BUS-DEFAULT"
      val actualType = transitInitializer.getVehicleType(routeInfo("217", "1342"), BeamMode.BUS).id.toString

      actualType shouldEqual expectedType
    }

    "return RAIL-DEFAULT, based on agency[DEFAULT] " in {
      val expectedType = "RAIL-DEFAULT"
      val actualType = transitInitializer.getVehicleType(routeInfo("DEFAULT", "dummy"), BeamMode.RAIL).id.toString

      actualType shouldEqual expectedType
    }

    "return BUS-DEFAULT, as a default vehicle type" in {
      val expectedType = "BUS-DEFAULT"
      val actualType = transitInitializer.getVehicleType(routeInfo("dummy", "dummy"), BeamMode.BUS).id.toString

      actualType shouldEqual expectedType
    }

    "not be BUS-AC, as vehicleTypes doesn't have it" in {
      val expectedType = "BUS-AC"
      val actualType = transitInitializer.getVehicleType(routeInfo("217", "1350"), BeamMode.BUS).id.toString

      actualType should not be expectedType
    }
  }

  private def routeInfo(agencyId: String, routeId: String) = {
    val route = new RouteInfo()
    route.agency_id = agencyId
    route.route_id = routeId
    route
  }

  private def init = {
    val beamConfig = BeamConfig(
      baseConfig
        .withValue(
          "beam.agentsim.agents.vehicles.transitVehicleTypesByRouteFile",
          ConfigValueFactory
            .fromAnyRef("test/test-resources/beam/router/transitVehicleTypesByRoute.csv")
        )
    )
    val vehicleTypes = readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath)
    val transitInitializer = new TransitVehicleInitializer(beamConfig, vehicleTypes)
    transitInitializer
  }
}
