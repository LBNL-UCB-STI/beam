package beam.agentsim.agents.ridehail
import beam.agentsim.agents.planning.BeamPlan
import org.jgrapht.graph.{DefaultDirectedGraph, DefaultEdge}
import org.matsim.api.core.v01.population.{Activity, Person, Plan}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.vehicles.VehicleUtils
import org.scalatest.{FlatSpec, Matchers}
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class AlonsoMoraPoolingAlgTest extends FlatSpec with Matchers {

  behavior of "AlonsoMoraPoolingAlg"

  it should " " in {
//    val plans = scenario1
    //val skim = SkimUtils.calcFacilityToFacilitySkim(null, plans)
    val omega: Double = 5 * 60
    val delta: Double = 2 * 5 * 60
//    var persons = List[MSAPerson]()
//    for (p <- plans) {
//      for (i <- 0 until p.trips.size) {
//        if (p.trips(i).leg.isDefined) {
//          persons = new MSAPerson(p.getPerson, p.trips(i - 1).activity, p.trips(i).activity) +: persons
//        }
//      }
//    }

    val sc = scenario2()
    val g : RVGraph = AlonsoMoraPoolingAlg(sc._2, sc._1).getPairwiseRVGraph()
    for(e <- g.edgeSet.asScala) {
      println(g.getEdgeSource(e) + " --> " + g.getEdgeTarget(e))
    }
  }

  def scenario1(): List[BeamPlan] = {
    val population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val P: Person = population.getFactory.createPerson(Id.createPersonId("p-0-1"))
    val homeCoord = new Coord(0, 0)
    val H11: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H11.setEndTime(8.5 * 3600)
    val W1: Activity = PopulationUtils.createActivityFromCoord("work", new Coord(30, 0))
    W1.setStartTime(9 * 3600)
    W1.setEndTime(17 * 3600)
    val H12: Activity = PopulationUtils.createActivityFromCoord("home", homeCoord)
    H12.setStartTime(17.5 * 3600)
    val plan: Plan = population.getFactory.createPlan()
    plan.setPerson(P)
    plan.addActivity(H11)
    plan.addActivity(W1)
    plan.addActivity(H12)
    List[BeamPlan](BeamPlan(plan))
  }

  def scenario2(): (List[MSAVehicle], List[MSAPerson]) = {
    val v1: MSAVehicle = MSAVehicle(newVehicle("v1"), new Coord(20, 17), 4)
    val v2: MSAVehicle = MSAVehicle(newVehicle("v2"), new Coord(35, 17), 4)

    val p1: MSAPerson = MSAPerson(
      newPerson("p1"),
      new MSRPickup(new Coord(15, 20), seconds(8, 0)),
      new MSRDropoff(new Coord(65, 15), seconds(8, 50, 15))
    )
    val p2: MSAPerson = MSAPerson(
      newPerson("p2"),
      new MSRPickup(new Coord(25, 20), seconds(8, 1)),
      new MSRDropoff(new Coord(70, 15), seconds(8, 45, 17))
    )
    val p3: MSAPerson = MSAPerson(
      newPerson("p3"),
      new MSRPickup(new Coord(35, 15), seconds(8, 2)),
      new MSRDropoff(new Coord(200, 15), seconds(8, 50, 15))
    )
    val p4: MSAPerson = MSAPerson(
      newPerson("p4"),
      new MSRPickup(new Coord(15, 15), seconds(8, 3)),
      new MSRDropoff(new Coord(60, 15), seconds(8, 40))
    )

    (List(v1, v2), List(p1, p2, p3, p4))
  }

  def newVehicle(id: String): Vehicle = {
    VehicleUtils.getFactory.createVehicle(Id.createVehicleId(id), VehicleUtils.getDefaultVehicleType)
  }

  def newPerson(id: String): Person = {
    PopulationUtils.createPopulation(ConfigUtils.createConfig).getFactory.createPerson(Id.createPersonId(id))
  }
  def seconds(h: Double, m: Double, s: Double = 0): Double = h * 3600 + m * 60 + s

}
