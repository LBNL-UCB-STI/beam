package beam.agentsim.agents.ridehail

import akka.actor.ActorRef
import beam.agentsim.agents.ridehail.RideHailMatching.{CustomerRequest, RideHailTrip, VehicleAndSchedule}
import beam.agentsim.agents.vehicles.{BeamVehicleType, PersonIdWithActorRef}
import beam.router.skim.Skims
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.{BeamHelper, BeamScenario, BeamServices, Geofence}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigFactory}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.collections.QuadTree
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class MatchingAlgorithmsForRideHailSpec extends FlatSpec with Matchers with BeamHelper {

  "Running Alonso Mora Algorithm" must "creates a consistent plan" in {
    val config = ConfigFactory
      .parseString(
        """
           |beam.outputs.events.fileOutputFormats = xml
           |beam.physsim.skipPhysSim = true
           |beam.agentsim.lastIteration = 0
           |beam.agentsim.agents.rideHail.allocationManager.matchingAlgorithm = "ALONSOMORA_POOLING_ALG_FOR_RIDEHAIL"
           |beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec = 360
           |beam.agentsim.agents.rideHail.allocationManager.maxExcessRideTime = 0.2
           |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.maxRequestsPerVehicle = 4
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runConsistentPlanCheck(config)
  }

  "Running Async Alonso Mora Algorithm" must "creates a consistent plan" in {
    val config = ConfigFactory
      .parseString(
        """
          |beam.outputs.events.fileOutputFormats = xml
          |beam.physsim.skipPhysSim = true
          |beam.agentsim.lastIteration = 0
          |beam.agentsim.agents.rideHail.allocationManager.matchingAlgorithm = "ASYNC_ALONSOMORA_ALG_FOR_RIDEHAIL"
          |beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec = 360
          |beam.agentsim.agents.rideHail.allocationManager.maxExcessRideTime = 0.2
          |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.maxRequestsPerVehicle = 1000
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runConsistentPlanCheck(config)
  }

  "Running Vehicle Centric Matching Algorithm" must "creates a consistent plan" in {
    val config = ConfigFactory
      .parseString(
        """
          |beam.outputs.events.fileOutputFormats = xml
          |beam.physsim.skipPhysSim = true
          |beam.agentsim.lastIteration = 0
          |beam.agentsim.agents.rideHail.allocationManager.matchingAlgorithm = "VEHICLE_CENTRIC_MATCHING_FOR_RIDEHAIL"
          |beam.agentsim.agents.rideHail.allocationManager.maxWaitingTimeInSec = 360
          |beam.agentsim.agents.rideHail.allocationManager.maxExcessRideTime = 0.2
          |beam.agentsim.agents.rideHail.allocationManager.alonsoMora.maxRequestsPerVehicle = 1000
        """.stripMargin
      )
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runConsistentPlanCheck(config)
  }

  private def runConsistentPlanCheck(config: Config): Unit = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    implicit val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
        }
      }
    )
    implicit val services = injector.getInstance(classOf[BeamServices])
    implicit val actorRef = ActorRef.noSender
    Skims.setup
    val sc = MatchingAlgorithmsForRideHailSpec.scenario1
    val alg: AlonsoMoraMatchingWithMIPAssignment =
      new AlonsoMoraMatchingWithMIPAssignment(
        MatchingAlgorithmsForRideHailSpec.demandSpatialIndex(sc._2),
        sc._1,
        services
      )

    val rvGraph = alg.pairwiseRVGraph
    val rtvGraph = alg.rTVGraph(rvGraph)
    val assignment = alg.optimalAssignment(rtvGraph)

    assignment.foreach { row =>
      assert(row.getId == "trip:[p1] -> [p4] -> " || row.vehicle.get.getId == "trip:[p3] -> ")
      assert(row.getId == "v2" || row.vehicle.get.getId == "v1")
    }

    for (e <- rvGraph.edgeSet.asScala) {
      rvGraph.getEdgeSource(e).getId match {
        case "p1" =>
          assert(
            rvGraph.getEdgeTarget(e).getId.equals("p2") ||
            rvGraph.getEdgeTarget(e).getId.equals("p4")
          )
        case "p2" =>
          assert(
            rvGraph.getEdgeTarget(e).getId.equals("p1") ||
            rvGraph.getEdgeTarget(e).getId.equals("p3") ||
            rvGraph.getEdgeTarget(e).getId.equals("p4")
          )
        case "p3" =>
          assert(
            rvGraph.getEdgeTarget(e).getId.equals("p2")
          )
        case "p4" =>
          assert(
            rvGraph.getEdgeTarget(e).getId.equals("p1") ||
            rvGraph.getEdgeTarget(e).getId.equals("p2")
          )
        case "v1" =>
          assert(
            rvGraph.getEdgeTarget(e).getId.equals("p2") ||
            rvGraph.getEdgeTarget(e).getId.equals("p3")
          )
        case "v2" =>
          assert(
            rvGraph.getEdgeTarget(e).getId.equals("p1") ||
            rvGraph.getEdgeTarget(e).getId.equals("p2") ||
            rvGraph.getEdgeTarget(e).getId.equals("p3") ||
            rvGraph.getEdgeTarget(e).getId.equals("p4")
          )
      }
    }

    for (v <- rtvGraph.vertexSet().asScala.filter(_.isInstanceOf[RideHailTrip])) {
      v.getId match {
        case "trip:[p3] -> " =>
          assert(
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v1") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p3")
          )
        case "trip:[p1] -> " =>
          assert(
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p1") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2")
          )
        case "trip:[p2] -> " =>
          assert(
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p2") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2")
          )
        case "trip:[p4] -> " =>
          assert(
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p4") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2")
          )
        case "trip:[p1] -> [p4] -> " =>
          assert(
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p1") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p4")
          )
        case "trip:[p2] -> [p3] -> " =>
          assert(
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p2") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p3")
          )
        case "trip:[p2] -> [p4] -> " =>
          assert(
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("v2") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p2") ||
            rtvGraph.outgoingEdgesOf(v).asScala.map(e => rtvGraph.getEdgeTarget(e).getId).contains("p4")
          )
        case _ =>
      }
    }
  }
}

object MatchingAlgorithmsForRideHailSpec {

  def scenario1()(
    implicit
    services: BeamServices,
    beamScenario: BeamScenario,
    mockActorRef: ActorRef
  ): (List[VehicleAndSchedule], List[CustomerRequest]) = {
    import scala.concurrent.duration._
    val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val v1: VehicleAndSchedule =
      RideHailMatching.createVehicleAndSchedule(
        "v1",
        vehicleType,
        new Coord(5000, 5000),
        8.hours.toSeconds.toInt,
        None,
        4
      )
    val v2: VehicleAndSchedule =
      RideHailMatching.createVehicleAndSchedule(
        "v2",
        vehicleType,
        new Coord(2000, 2000),
        8.hours.toSeconds.toInt,
        None,
        4
      )
    val p1Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p1"),
        new Coord(1000, 2000),
        8.hours.toSeconds.toInt,
        new Coord(18000, 19000),
        services
      )
    val p4Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p4"),
        new Coord(2000, 1000),
        (8.hours.toSeconds + 5.minutes.toSeconds).toInt,
        new Coord(20000, 18000),
        services
      )
    val p2Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p2"),
        new Coord(3000, 3000),
        (8.hours.toSeconds + 1.minutes.toSeconds).toInt,
        new Coord(19000, 18000),
        services
      )
    val p3Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p3"),
        new Coord(4000, 4000),
        (8.hours.toSeconds + 2.minutes.toSeconds).toInt,
        new Coord(21000, 21000),
        services
      )
    (List(v1, v2), List(p1Req, p2Req, p3Req, p4Req))
  }

  def scenarioGeoFence()(
    implicit
    services: BeamServices,
    beamScenario: BeamScenario,
    mockActorRef: ActorRef
  ): (List[VehicleAndSchedule], List[CustomerRequest]) = {
    import scala.concurrent.duration._
    val vehicleType = beamScenario.vehicleTypes(Id.create("beamVilleCar", classOf[BeamVehicleType]))
    val v1: VehicleAndSchedule =
      RideHailMatching.createVehicleAndSchedule(
        "v1",
        vehicleType,
        new Coord(5000, 5000),
        8.hours.toSeconds.toInt,
        Some(Geofence(10000, 10000, 13400)),
        4
      )
    val v2: VehicleAndSchedule =
      RideHailMatching.createVehicleAndSchedule(
        "v2",
        vehicleType,
        new Coord(2000, 2000),
        8.hours.toSeconds.toInt,
        Some(Geofence(10000, 10000, 13400)),
        4
      )
    val p1Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p1"),
        new Coord(1000, 2000),
        8.hours.toSeconds.toInt,
        new Coord(18000, 19000),
        services
      )
    val p4Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p4"),
        new Coord(2000, 1000),
        (8.hours.toSeconds + 5.minutes.toSeconds).toInt,
        new Coord(20000, 18000),
        services
      )
    val p2Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p2"),
        new Coord(3000, 3000),
        (8.hours.toSeconds + 1.minutes.toSeconds).toInt,
        new Coord(19000, 18000),
        services
      )
    val p3Req: CustomerRequest =
      RideHailMatching.createPersonRequest(
        makeVehPersonId("p3"),
        new Coord(4000, 4000),
        (8.hours.toSeconds + 2.minutes.toSeconds).toInt,
        new Coord(21000, 20000),
        services
      )
    (List(v1, v2), List(p1Req, p2Req, p3Req, p4Req))
  }

  def makeVehPersonId(perId: Id[Person])(implicit mockActorRef: ActorRef): PersonIdWithActorRef =
    PersonIdWithActorRef(perId, mockActorRef)

  def makeVehPersonId(perId: String)(implicit mockActorRef: ActorRef): PersonIdWithActorRef =
    makeVehPersonId(Id.create(perId, classOf[Person]))

  def demandSpatialIndex(demand: List[CustomerRequest]): QuadTree[CustomerRequest] = {
    val boundingBox: Envelope = getEnvelopeFromDemand(demand)
    val spatialDemand = new QuadTree[CustomerRequest](
      boundingBox.getMinX,
      boundingBox.getMinY,
      boundingBox.getMaxX,
      boundingBox.getMaxY
    )
    demand.foreach { d =>
      spatialDemand.put(d.pickup.activity.getCoord.getX, d.pickup.activity.getCoord.getY, d)
    }
    spatialDemand
  }

  def getEnvelopeFromDemand(demand: List[CustomerRequest]): Envelope = {
    val minx = demand.map(_.pickup.activity.getCoord.getX).min
    val maxx = demand.map(_.pickup.activity.getCoord.getX).max
    val miny = demand.map(_.pickup.activity.getCoord.getY).min
    val maxy = demand.map(_.pickup.activity.getCoord.getY).max
    new Envelope(minx, maxx, miny, maxy)
  }

}
