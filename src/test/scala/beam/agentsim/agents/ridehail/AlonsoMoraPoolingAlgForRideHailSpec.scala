package beam.agentsim.agents.ridehail

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.{TestKit, TestProbe}
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail.{CustomerRequest, RVGraph, VehicleAndSchedule, _}
import beam.router.BeamSkimmer
import beam.utils.DefaultVehicleTypeUtils
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Coord
import org.scalatest.FunSpecLike

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class AlonsoMoraPoolingAlgForRideHailSpec
    extends TestKit(
      ActorSystem(
        name = "AlonsoMoraPoolingAlgForRideHailSpec",
        config = ConfigFactory
          .parseString("""
               akka.log-dead-letters = 10
               akka.actor.debug.fsm = true
               akka.loglevel = debug
            """)
          .withFallback(testConfig("test/input/beamville/beam.conf").resolve())
      )
    )
    with FunSpecLike {

  val probe: TestProbe = TestProbe.apply()
  implicit val mockActorRef: ActorRef = probe.ref

  describe("AlonsoMoraPoolingAlgForRideHail") {
    it("Creates a consistent plan") {
      implicit val skimmer: BeamSkimmer = new BeamSkimmer()
      val sc = AlonsoMoraPoolingAlgForRideHailSpec.scenario1
      val alg: AlonsoMoraPoolingAlgForRideHail =
        new AlonsoMoraPoolingAlgForRideHail(
          AlonsoMoraPoolingAlgForRideHail.demandSpatialIndex(sc._2),
          sc._1,
          Map[MobilityServiceRequestType, Int]((Pickup, 6 * 60), (Dropoff, 10 * 60)),
          maxRequestsPerVehicle = 1000
        )

      val rvGraph: RVGraph = alg.pairwiseRVGraph
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

      val rtvGraph = alg.rTVGraph(rvGraph)

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

      val assignment = alg.greedyAssignment(rtvGraph)

      for (row <- assignment) {
        assert(row._1.getId == "trip:[p1] -> [p4] -> " || row._1.getId == "trip:[p3] -> ")
        assert(row._2.getId == "v2" || row._2.getId == "v1")
      }
    }
  }

}

object AlonsoMoraPoolingAlgForRideHailSpec {

  def scenario1(
    implicit skimmer: BeamSkimmer,
    mockActorRef: ActorRef
  ): (List[VehicleAndSchedule], List[CustomerRequest]) = {
    val v1: VehicleAndSchedule = createVehicleAndSchedule(
      "v1",
      DefaultVehicleTypeUtils.defaultCarBeamVehicleType,
      new Coord(5000, 5000),
      seconds(8, 0)
    )
    val v2: VehicleAndSchedule = createVehicleAndSchedule(
      "v2",
      DefaultVehicleTypeUtils.defaultCarBeamVehicleType,
      new Coord(2000, 2000),
      seconds(8, 0)
    )
    val p1Req: CustomerRequest =
      createPersonRequest(makeVehPersonId("p1"), new Coord(1000, 2000), seconds(8, 0), new Coord(18000, 19000))
    val p4Req: CustomerRequest =
      createPersonRequest(makeVehPersonId("p4"), new Coord(2000, 1000), seconds(8, 5), new Coord(20000, 18000))
    val p2Req: CustomerRequest =
      createPersonRequest(makeVehPersonId("p2"), new Coord(3000, 3000), seconds(8, 1), new Coord(19000, 18000))
    val p3Req: CustomerRequest =
      createPersonRequest(makeVehPersonId("p3"), new Coord(4000, 4000), seconds(8, 2), new Coord(21000, 20000))
    (List(v1, v2), List(p1Req, p2Req, p3Req, p4Req))
  }
}
