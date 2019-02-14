package beam.agentsim.agents.ridehail

import akka.actor.{ActorRef, ActorSystem, DeadLetter}
import akka.testkit.{TestKit, TestProbe}
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail.{CustomerRequest, RVGraph, VehicleAndSchedule}
import beam.agentsim.agents.vehicles.VehiclePersonId
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import org.scalatest.{FlatSpec, FunSpecLike, Matchers}

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class AlonsoMoraPoolingAlgForRideHailSpec extends TestKit(
  ActorSystem(
    name = "PersonAgentSpec",
    config = ConfigFactory
    .parseString(
    """
    akka.log-dead-letters = 10
    akka.actor.debug.fsm = true
    akka.loglevel = debug
    """
    ))
  )with FunSpecLike with Matchers {

  val probe: TestProbe = TestProbe.apply()
  val mockActorRef = probe.ref

  describe ("AlonsoMoraPoolingAlgForRideHail") {
    it
    "Creates a consistent plan" {

      val skimmer: BeamSkimmer = new BeamSkimmer()
      val sc = scenario1(skimmer)
      val algo: AlonsoMoraPoolingAlgForRideHail =
        new AlonsoMoraPoolingAlgForRideHail(
          sc._2,
          sc._1,
          omega = 6 * 60,
          delta = 10 * 5000 * 60,
          radius = Int.MaxValue,
          skimmer
        )
      val rvGraph: RVGraph = algo.pairwiseRVGraph
      for (e <- rvGraph.edgeSet.asScala) {
        rvGraph.getEdgeSource(e).getId match {
          case _ =>
          case "p1" =>
            assert(rvGraph.getEdgeTarget(e).getId.equals("p2") ||  rvGraph.getEdgeTarget(e).getId.equals("p4"))
//          case "p2" => rvGraph.getEdgeTarget(e).getId should (equal("p1") or (equal("p3") or equal("p4")))
//          case "p3" => rvGraph.getEdgeTarget(e).getId should equal("p2")
//          case "p4" => rvGraph.getEdgeTarget(e).getId should (equal("p1") or equal("p2"))
//          case "v1" => rvGraph.getEdgeTarget(e).getId should (equal("p2") or equal("p3"))
//          case "v2" =>
//            rvGraph.getEdgeTarget(e).getId should (equal("p1") or (equal("p2") or (equal("p3") or equal("p4"))))
        }
      }
//      for (e <- rvGraph.edgeSet.asScala) {
//        println(rvGraph.getEdgeSource(e) + " <-> " + rvGraph.getEdgeTarget(e))
//      }
//      val rtvGraph = algo.rTVGraph(rvGraph)
//      println("------")
//      for (e <- rtvGraph.edgeSet.asScala) {
//        println(rtvGraph.getEdgeSource(e) + " <-> " + rtvGraph.getEdgeTarget(e))
//      }
//      val assignment = algo.greedyAssignment(rtvGraph)
//      println("------")
//      for (row <- assignment) {
//        println(row)
//      }
      0
    }
  }

  def scenario1(implicit skimmer: BeamSkimmer): (List[VehicleAndSchedule], List[CustomerRequest]) = {
    val v1: VehicleAndSchedule = createVehicleAndSchedule("v1", new Coord(5000, 5000), seconds(8, 0))
    val v2: VehicleAndSchedule = createVehicleAndSchedule("v2", new Coord(2000, 2000), seconds(8, 0))

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

  def makeVehPersonId(perId: String) = VehiclePersonId(Id.create(perId,classOf[Vehicle]),Id.create(perId,classOf[Person]),mockActorRef)


}
