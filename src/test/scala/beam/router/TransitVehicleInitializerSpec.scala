package beam.router

import beam.agentsim.agents.TransitVehicleInitializer
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.integration.IntegrationSpecCommon
import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig
import beam.utils.BeamVehicleUtils.readBeamVehicleTypeFile
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.conveyal.r5.transit.RouteInfo
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.Id
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class TransitVehicleInitializerSpec extends AnyWordSpecLike with Matchers with IntegrationSpecCommon {
  private val arbitraryVehicleId = Id.createVehicleId("dummy-vehicle-id")
  "getVehicleType" should {
    val transitInitializer: TransitVehicleInitializer =
      init("test/test-resources/beam/router/transitVehicleTypesByRoute.csv")


    "return SUV, based on agency[217] and route[1342] map" in {
      val expectedType = "BUS-DEFAULT"
      val actualType = transitInitializer.getVehicleType(routeInfo("217", "1342"), arbitraryVehicleId, BeamMode.BUS).id.toString

      actualType shouldEqual expectedType
    }

    "return RAIL-DEFAULT, based on agency[DEFAULT] " in {
      val expectedType = "RAIL-DEFAULT"
      val actualType = transitInitializer.getVehicleType(routeInfo("DEFAULT", "dummy"), arbitraryVehicleId, BeamMode.RAIL).id.toString

      actualType shouldEqual expectedType
    }

    "return BUS-DEFAULT, as a default vehicle type" in {
      val expectedType = "BUS-DEFAULT"
      val actualType = transitInitializer.getVehicleType(routeInfo("dummy", "dummy"), arbitraryVehicleId, BeamMode.BUS).id.toString

      actualType shouldEqual expectedType
    }

    "not be BUS-AC, as vehicleTypes doesn't have it" in {
      val expectedType = "BUS-AC"
      val actualType = transitInitializer.getVehicleType(routeInfo("217", "1350"), arbitraryVehicleId, BeamMode.BUS).id.toString

      actualType should not be expectedType
    }
  }
  "getVehicleType" when {
    "provided with routeIds/tripIds" should {
      val transitInitializer: TransitVehicleInitializer =
        init("test/test-resources/beam/router/transitVehicleTypesByRouteWithTripId.csv")
      "return SUV, based on agency[217] and route[1342] map" in {
        val actualType = transitInitializer.getVehicleType(routeInfo("217", "1342"), arbitraryVehicleId, BeamMode.BUS)

        actualType.id.toString shouldEqual "BUS-DEFAULT"
      }
      "return RAIL-DEFAULT, based on agency[217] and route[1342] and trip[1] map" in {
        val actualType =
          transitInitializer.getVehicleType(routeInfo("217", "1342"), "1".createId[BeamVehicle], BeamMode.BUS)

        actualType.id.toString shouldEqual "RAIL-DEFAULT"
      }
      "return TRAM-DEFAULT, based on agency[3234] and route[12] map" in {
        val actualType =
          transitInitializer.getVehicleType(routeInfo("3234", "12"), "2".createId[BeamVehicle], BeamMode.BUS)

        actualType.id.toString shouldEqual "TRAM-DEFAULT"
      }
    }
  }

  "loadGtfsVehicleTypes" should {
    "load vehicle types" in {
      val result =
        TransitVehicleInitializer.loadGtfsVehicleTypes("test/test-resources/beam/router/transitVehicleTypesByRoute.csv")
      result shouldBe (
        Map(
          "217" -> Map(
            ("1342", None) -> "SUV",
            ("1350", None) -> "BUS-AC",
            ("22", None)   -> "BUS-DEFAULT"
          ),
          "3234" -> Map(
            ("12", None)   -> "CABLE_CAR",
            ("1350", None) -> "BABAR"
          )
        )
      )
    }
    "load vehicle types with trip ids" in {
      val result = TransitVehicleInitializer.loadGtfsVehicleTypes(
        "test/test-resources/beam/router/transitVehicleTypesByRouteWithTripId.csv"
      )

      result shouldBe (
        Map(
          "217" -> Map(
            ("1342", None)                            -> "SUV",
            ("1342", Some("1".createId[BeamVehicle])) -> "RAIL-DEFAULT",
            ("22", Some("2".createId[BeamVehicle]))   -> "BUS-DEFAULT"
          ),
          "3234" -> Map(
            ("12", None)   -> "TRAM-DEFAULT",
            ("1350", None) -> "BABAR"
          )
        )
      )
    }
  }

  private def routeInfo(agencyId: String, routeId: String) = {
    val route = new RouteInfo()
    route.agency_id = agencyId
    route.route_id = routeId
    route
  }

  private def init(filePath: String) = {
    val beamConfig = BeamConfig(
      baseConfig
        .withValue(
          "beam.agentsim.agents.vehicles.transitVehicleTypesByRouteFile",
          ConfigValueFactory
            .fromAnyRef(filePath)
        )
    )

    val vehicleTypes = readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath)
    val transitInitializer = new TransitVehicleInitializer(beamConfig, vehicleTypes)
    transitInitializer
  }
}
