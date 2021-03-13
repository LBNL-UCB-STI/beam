package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.choice.logit.UtilityFunctionOperation
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory, VehicleManager}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.agentsim.infrastructure.{ParkingInquiry, ZonalParkingManager, ZonalParkingManagerFunctions}
import beam.router.BeamRouter
import beam.sim.common.{GeoUtils, SimpleGeoUtils}
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint
import beam.utils.BeamVehicleUtils
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Id
import org.scalatest.{Matchers, WordSpec}

import scala.collection.mutable

class ParkingZoneSearchTest extends WordSpec with Matchers {

  "parking zone search" should {
    "return not emergency stall" in {
      val searchStartRadius = 10
      val searchMaxRadius = 100000
      val localCRS = "epsg:26910"
      val geo: GeoUtils = SimpleGeoUtils(localCRS)
      val minx = 442704.6679029416
      val maxx = 665356.5467613721
      val miny = 4073548.042681266
      val maxy = 4313187.271314456
      val boundingBox: Envelope = new Envelope(minx, maxx, miny, maxy)
      val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile(
        "production/sfbay/gemini/vehicle-tech/vehicletypes-Base_2035_20210204_updated.csv"
      )
      val rideHailVehicleManager: VehicleManager =
        VehicleManager.create(
          Id.create("ride-hail-default", classOf[VehicleManager]),
          Some(VehicleCategory.Car),
          isRideHail = true
        )

      val beamVehicle: BeamVehicle = new BeamVehicle(
        Id.createVehicleId("rideHailVehicle-31453@default"),
        new Powertrain(626.0),
        vehicleTypes(Id.create("ev-L1-100000-to-100000000-LowTech-2035-Midsize-BEV_300_XFC", classOf[BeamVehicleType])),
        // managerId = VehicleManager.privateVehicleManager.managerId
        managerId = rideHailVehicleManager.managerId
      )

      val chargingPointConfig: BeamConfig.Beam.Agentsim.ChargingNetworkManager.ChargingPoint =
        new ChargingPoint(thresholdXFCinKW = 250, thresholdDCFCinKW = 50)

      val rand = new scala.util.Random(seed = 42)

      val tazMap: TAZTreeMap = TAZTreeMap.getTazTreeMap("production/sfbay/taz-centers.csv")
      val parkingFilePath1 = "production/sfbay/parking/gemini_depot_parking_power_150kw.csv"
      val parkingFilePath2 = "production/sfbay/parking/gemini_taz_parking_plugs_power_150kw.csv"

      val (parkingZones, parkingSearchTree) = ZonalParkingManager.loadParkingZones[TAZ](
        parkingFilePath2,
        tazMap.tazQuadTree,
        parkingStallCountScalingFactor = 0.1,
        parkingCostScalingFactor = 1.0,
        rand
      )

      val inquiry: ParkingInquiry = ParkingInquiry(
        destinationUtm = new BeamRouter.Location(593064.3984395313, 4135625.9699497805),
        activityType = "fast-charge",
        beamVehicle = Some(beamVehicle),
        remainingTripData = None,
        requestId = 9087
      )

      val mnlMultiplierParameters: Map[ParkingMNL.Parameters, UtilityFunctionOperation.Multiplier] = Map(
        ParkingMNL.Parameters.RangeAnxietyCost                      -> UtilityFunctionOperation.Multiplier(50),
        ParkingMNL.Parameters.WalkingEgressCost                     -> UtilityFunctionOperation.Multiplier(0),
        ParkingMNL.Parameters.ParkingTicketCost                     -> UtilityFunctionOperation.Multiplier(0),
        ParkingMNL.Parameters.HomeActivityPrefersResidentialParking -> UtilityFunctionOperation.Multiplier(-0.3)
      )

      val vehicleManagers: Map[Id[VehicleManager], VehicleManager] = {
        val managers = mutable.HashMap.empty[Id[VehicleManager], VehicleManager]
        managers.put(VehicleManager.privateVehicleManager.managerId, VehicleManager.privateVehicleManager)
        managers.put(VehicleManager.transitVehicleManager.managerId, VehicleManager.transitVehicleManager)
        managers.put(VehicleManager.bodiesVehicleManager.managerId, VehicleManager.bodiesVehicleManager)
        managers.put(rideHailVehicleManager.managerId, rideHailVehicleManager)
        managers.toMap
      }

      val tazSearchFunctions: ZonalParkingManagerFunctions[TAZ] = new ZonalParkingManagerFunctions[TAZ](
        tazMap.tazQuadTree,
        tazMap.idToTAZMapping,
        identity[TAZ],
        geo,
        parkingZones,
        parkingSearchTree,
        rand,
        searchStartRadius,
        searchMaxRadius,
        boundingBox,
        mnlMultiplierParameters,
        vehicleManagers,
        chargingPointConfig
      )

      val searchResult: ParkingZoneSearch.ParkingZoneSearchResult[TAZ] =
        tazSearchFunctions.searchForParkingStall(inquiry)

      assert(searchResult.parkingStall.chargingPointType.isDefined)
    }
  }
}
