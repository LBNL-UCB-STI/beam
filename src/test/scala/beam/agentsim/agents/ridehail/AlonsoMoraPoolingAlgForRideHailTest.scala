package beam.agentsim.agents.ridehail

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.BeamRouter.Location
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.agentsim.agents.planning.Trip
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.vehicles.VehicleUtils
import org.scalatest.{FlatSpec, Matchers}
import org.matsim.vehicles.Vehicle

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
    val rvGraph: RVGraph = algo.getPairwiseRVGraph
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
    println("------")
    val rtvGraph = algo.getRTVGraph(rvGraph)
    for (e <- rtvGraph.edgeSet.asScala) {
      println(rtvGraph.getEdgeSource(e) + " <-> " + rtvGraph.getEdgeTarget(e))
    }
  }

  def scenario1(implicit skimmer: BeamSkimmer): (List[RideHailVehicle], List[CustomerRequest]) = {
    val v1: RideHailVehicle = createVehiclePassengers("v1", new Coord(5000, 5000), seconds(8, 0))
    val v2: RideHailVehicle = createVehiclePassengers("v2", new Coord(2000, 2000), seconds(8, 0))

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

  // ************ Helper functions ************
  def newVehicle(id: String): Vehicle = {
    VehicleUtils.getFactory.createVehicle(Id.createVehicleId(id), VehicleUtils.getDefaultVehicleType)
  }

  def newPerson(id: String): Person = {
    PopulationUtils.createPopulation(ConfigUtils.createConfig).getFactory.createPerson(Id.createPersonId(id))
  }

  def seconds(h: Int, m: Int, s: Int = 0): Int = h * 3600 + m * 60 + s

  def createPersonRequest(pid: String, src: Location, srcTime: Int, dst: Location)(
    implicit skimmer: BeamSkimmer
  ): CustomerRequest = {
    val p1 = newPerson(pid)
    val p1Act1: Activity = PopulationUtils.createActivityFromCoord(s"${pid}Act1", src)
    p1Act1.setEndTime(srcTime)
    val p1Act2: Activity = PopulationUtils.createActivityFromCoord(s"${pid}Act2", dst)
    val p1_tt: Int = skimmer
      .getTimeDistanceAndCost(
        p1Act1.getCoord,
        p1Act2.getCoord,
        0,
        BeamMode.CAR,
        BeamVehicleType.defaultCarBeamVehicleType.id
      )
      .timeAndCost
      .time
      .get
    CustomerRequest(
      p1,
      MobilityServiceRequest(
        Some(p1.getId),
        p1Act1,
        srcTime,
        Trip(p1Act1, None, null),
        BeamMode.RIDE_HAIL,
        Pickup,
        srcTime
      ),
      MobilityServiceRequest(
        Some(p1.getId),
        p1Act2,
        srcTime + p1_tt,
        Trip(p1Act2, None, null),
        BeamMode.RIDE_HAIL,
        Dropoff,
        srcTime + p1_tt
      ),
    )
  }

  def createVehiclePassengers(vid: String, dst: Location, dstTime: Int): RideHailVehicle = {
    val v1 = newVehicle(vid)
    val v1Act0: Activity = PopulationUtils.createActivityFromCoord(s"${vid}Act0", dst)
    v1Act0.setEndTime(dstTime)
    RideHailVehicle(
      v1,
      List(
        MobilityServiceRequest(
          None,
          v1Act0,
          dstTime,
          Trip(v1Act0, None, null),
          BeamMode.RIDE_HAIL,
          Dropoff,
          dstTime
        )
      )
    )
  }

}
