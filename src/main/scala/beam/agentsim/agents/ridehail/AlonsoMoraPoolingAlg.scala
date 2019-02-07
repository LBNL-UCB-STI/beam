package beam.agentsim.agents.ridehail

import org.jgrapht.graph.{DefaultEdge, SimpleDirectedWeightedGraph}
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.List

// MS for MobilityService
sealed trait MSRequest {
  var coord: Coord
  var time: Double
}
case class MSRPickup(var coord: Coord, var time: Double) extends MSRequest
case class MSRDropoff(var coord: Coord, var time: Double) extends MSRequest

sealed trait MSAgent

case class MSAPerson(person: Person, pickup: MSRPickup, dropoff: MSRDropoff) extends MSAgent {
  override def toString = s"(${person.getId})"
//  override def getLocation = pickup
//  override def getDestinations = List(dropoff)
  var pickupTime: Double = Double.NaN // lbPickupTime <= pickupTime <= ubPickupTime
  var dropoffTime: Double = Double.NaN // lbDropoffTime <= dropoffTime <= ubDropoffTime
  def this(person: Person, src: Activity, dst: Activity) =
    this(person, new MSRPickup(src.getCoord, src.getEndTime), new MSRDropoff(dst.getCoord, dst.getStartTime))

  // required to copy the requests for alteration later on
  def requestsQ : List[(MSRequest, MSAPerson)] =
    return List(
      (new MSRPickup(this.pickup.coord, this.pickup.time), this),
      (new MSRDropoff(this.dropoff.coord, this.dropoff.time), this)
    )
}

//case class MSIVehicle(vehicle: Vehicle, location: MSRStart, passengers: List[MSIPerson], maxOccupancy: Int)
//  extends MobilityServiceInteraction {
//  override def toString = s"vehicle ${vehicle.getId}"
//  override def getLocation = location
//  override def getDestinations = passengers.map(_.dropoff)
//}

case class MSAVehicle(vehicle: Vehicle, initLocation: Coord, maxOccupancy: Int) extends MSAgent {
  override def toString = {
    var str = s"[${vehicle.getId}]"
    passengers.foreach{x=>str=str+s"${x}"}
    str
  }
  var location: Coord = initLocation
  var passengers = List[MSAPerson]()

  def getSumOfDelays: Double =
    passengers.map(_.dropoffTime).sum -
    passengers.map(_.dropoff.time).sum +
    passengers.map(_.pickupTime).sum -
    passengers.map(_.pickup.time).sum
  def getFreeSeats = maxOccupancy - passengers.size
}

class MSMatch(taskQueue : List[(MSRequest, MSAPerson)]) extends DefaultEdge {
  override def toString = s"" + taskQueue.foreach(x => s"[${x._1.getClass.getSimpleName}-${x._2}]")
}

case class RVGraph(clazz: Class[MSMatch]) extends SimpleDirectedWeightedGraph[MSAgent, MSMatch](clazz)

case class AlonsoMoraPoolingAlg(demand: List[MSAPerson], supply: List[MSAVehicle]) {

  val omega: Double = 5 * 60
  val delta: Double = 2 * 5 * 60
  val radius: Double = 11

  def checkFeasibility(req: List[(MSRequest, MSAPerson)]): Option[List[(MSRequest, MSAPerson)]] = {
    for (i <- 0 until req.length - 1) {
      val tt = beam.sim.common.GeoUtils.distFormula(req(i)._1.coord, req(i + 1)._1.coord) * 60
      val window: Double = req(i + 1)._1 match {
        case _: MSRPickup  => omega
        case _: MSRDropoff => delta
        case _             => 0
      }
      if (req(i + 1)._1.time > req(i)._1.time + tt - window) {
        return None
      } else {
        req(i + 1)._1.time = req(i)._1.time + tt
      }
    }
    return Some(req)
  }

  def getAgentsWithinRadius(v: MSAVehicle): List[MSAPerson] = {
    return demand.collect {
      case x if beam.sim.common.GeoUtils.distFormula(x.pickup.coord, v.location) <= radius => x
    }
  }

  def getPairwiseRVGraph(): RVGraph = {
    val g = new RVGraph(classOf[MSMatch])
    var i = 0
    for (r1 <- demand) {
      i += 1
      for (r2 <- demand.drop(i)) {
        val req = (r1.requestsQ ++ r2.requestsQ).sortWith(_._1.time < _._1.time)
        if (req(0)._2 != req(1)._2) {
          checkFeasibility(req) match {
            case Some(x) =>
              g.addVertex(x(0)._2)
              g.addVertex(x(1)._2)
              g.addEdge(x(0)._2, x(1)._2, new MSMatch(x))
            case None =>
              // ?
          }
        }
      }
    }
    for (v <- supply) {
      var req = List[(MSRequest, MSAPerson)]()
      for (p: MSAPerson <- v.passengers) {
        req = (req ++ p.requestsQ).sortWith(_._1.time < _._1.time)
      }
      for (r <- getAgentsWithinRadius(v)) {
        val new_req = (req ++ r.requestsQ).sortWith(_._1.time < _._1.time)
        checkFeasibility(new_req) match {
          case Some(x) =>
            g.addVertex(v)
            g.addVertex(r)
            g.addEdge(v, r, new MSMatch(x))
          case None =>
            // ?
        }
      }
    }
    return g
  }
}
