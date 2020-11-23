package beam.router

import java.nio.file.Paths

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Access, AccessAndEgress, Location, RoutingRequest}
import beam.router.Modes.BeamMode.WALK
import beam.router.graphhopper.{CarGraphHopperWrapper, GraphHopperWrapper, WalkGraphHopperWrapper}
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import com.conveyal.osmlib.OSM
import com.typesafe.config.Config
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.WordSpecLike

/**
  * @author Dmitry Openkov
  */
class HybridRouterSpec extends WordSpecLike with BeamHelper {
  private val config: Config = testConfig("test/input/sf-light/sf-light.conf").resolve()

  "CompositeRouter" should {
    "return appropriate routes" in {
      val workerParams: R5Parameters = R5Parameters.fromConfig(config)
      val walkGHDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "walk-gh").toString
      val carGHDir: String = Paths.get(workerParams.beamConfig.beam.inputDirectory, "car-gh").toString

      val id2Link: Map[Int, (Location, Location)] = workerParams.networkHelper.allLinks
        .map(x => x.getId.toString.toInt -> (x.getFromNode.getCoord -> x.getToNode.getCoord))
        .toMap

      val r5 = new R5Wrapper(workerParams, new FreeFlowTravelTime, travelTimeNoiseFraction = 0)
      GraphHopperWrapper.createWalkGraphDirectoryFromR5(
        workerParams.transportNetwork,
        new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
        walkGHDir
      )
      GraphHopperWrapper.createCarGraphDirectoryFromR5(
        "staticGH",
        workerParams.transportNetwork,
        new OSM(workerParams.beamConfig.beam.routing.r5.osmMapdbFile),
        carGHDir,
        Map.empty
      )
      val walkGH = new WalkGraphHopperWrapper(walkGHDir, workerParams.geo, id2Link)
      val carGH = new CarGraphHopperWrapper(
        "staticGH",
        carGHDir,
        workerParams.geo,
        workerParams.vehicleTypes,
        workerParams.fuelTypePrices,
        Map.empty,
        id2Link
      )

      val hybridRouter = new HybridRouter(workerParams.transportNetwork, workerParams.geo, r5, carGH, walkGH)

      val origin = workerParams.geo.wgs2Utm(new Coord(-122.396944, 37.79288)) // Embarcadero
      val destination = workerParams.geo.wgs2Utm(new Coord(-122.460555, 37.764294)) // Near UCSF medical center
      val time = 25740
      val request = RoutingRequest(
        originUTM = origin,
        destinationUTM = destination,
        departureTime = time,
        withTransit = true,
        streetVehicles = Vector(
          StreetVehicle(
            Id.createVehicleId("rideHailVehicle-person=17673-0"),
            Id.create("Car", classOf[BeamVehicleType]),
            new SpaceTime(new Coord(origin.getX, origin.getY), time),
            Modes.BeamMode.CAR,
            asDriver = false
          ),
          StreetVehicle(
            Id.createVehicleId("body-667520-0"),
            Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
            new SpaceTime(origin, time),
            WALK,
            asDriver = true
          )
        ),
        streetVehiclesUseIntermodalUse = Access
      )
      val response = hybridRouter.calcRoute(request)
      println(response)
    }
  }
}
