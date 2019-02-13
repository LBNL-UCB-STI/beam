package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail.{CustomerRequest, RVGraph, RideHailVehicle}
import org.matsim.api.core.v01.{Coord}
import org.scalatest.{FlatSpec, Matchers}

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class AlonsoMoraPoolingAlgForRideHailTest extends FlatSpec with Matchers {

  behavior of "AlonsoMoraPoolingAlgForRideHail"

  it should " " in {
    val config = org.matsim.core.config.ConfigUtils.createConfig()
    val scenario: org.matsim.api.core.v01.Scenario = org.matsim.core.scenario.ScenarioUtils.createScenario(config)
    val skimmer: BeamSkimmer = new BeamSkimmer(scenario = scenario)
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
        case "p1" => rvGraph.getEdgeTarget(e).getId should (equal("p2") or equal("p4"))
        case "p2" => rvGraph.getEdgeTarget(e).getId should (equal("p1") or (equal("p3") or equal("p4")))
        case "p3" => rvGraph.getEdgeTarget(e).getId should equal("p2")
        case "p4" => rvGraph.getEdgeTarget(e).getId should (equal("p1") or equal("p2"))
        case "v1" => rvGraph.getEdgeTarget(e).getId should (equal("p2") or equal("p3"))
        case "v2" =>
          rvGraph.getEdgeTarget(e).getId should (equal("p1") or (equal("p2") or (equal("p3") or equal("p4"))))
      }
    }
    for (e <- rvGraph.edgeSet.asScala) {
      println(rvGraph.getEdgeSource(e) + " <-> " + rvGraph.getEdgeTarget(e))
    }

    val rtvGraph = algo.rTVGraph(rvGraph)
    println("------")
    for (e <- rtvGraph.edgeSet.asScala) {
      println(rtvGraph.getEdgeSource(e) + " <-> " + rtvGraph.getEdgeTarget(e))
    }

    val assignment = algo.greedyAssignment(rtvGraph)
    println("------")
    for (row <- assignment) {
      println(row)
    }
  }

  def scenario1(implicit skimmer: BeamSkimmer): (List[RideHailVehicle], List[CustomerRequest]) = {
    val v1: RideHailVehicle = AlonsoMoraPoolingAlgForRideHail.createVehiclePassengers("v1", new Coord(5000, 5000), seconds(8, 0))
    val v2: RideHailVehicle = AlonsoMoraPoolingAlgForRideHail.createVehiclePassengers("v2", new Coord(2000, 2000), seconds(8, 0))

    val p1Req: CustomerRequest =
      createPersonRequest("p1", new Coord(1000, 2000), seconds(8, 0), new Coord(18000, 19000))
    val p4Req: CustomerRequest =
      createPersonRequest("p4", new Coord(2000, 1000), seconds(8, 5), new Coord(20000, 18000))
    val p2Req: CustomerRequest =
      createPersonRequest("p2", new Coord(3000, 3000), seconds(8, 1), new Coord(19000, 18000))
    val p3Req: CustomerRequest =
      createPersonRequest("p3", new Coord(4000, 4000), seconds(8, 2), new Coord(21000, 20000))

    (List(v1, v2), List(p1Req, p2Req, p3Req, p4Req))
  }


}
