package beam.agentsim.agents.ridehail

import beam.agentsim.agents.planning.Trip
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.router.{BeamSkimmer, TimeDistanceAndCost}
import beam.router.Modes.BeamMode
import org.jgrapht.graph.{DefaultEdge, SimpleDirectedWeightedGraph}
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.vehicles.Vehicle

import scala.collection.immutable.List

// MS for MobilityService
sealed trait MobilityServiceRequestType
case object Pickup extends MobilityServiceRequestType
case object Dropoff extends MobilityServiceRequestType
case object Relocation extends MobilityServiceRequestType
case object Init extends MobilityServiceRequestType

case class MobilityServiceRequest(
  person: Option[org.matsim.api.core.v01.Id[Person]],
  activity: Activity,
  time: Double,
  trip: Trip,
  defaultMode: BeamMode,
  tag: MobilityServiceRequestType,
  serviceTime: Double,
  routingRequestId: Option[Int] = None
) {
  val nextActivity = Some(trip.activity)

  def formatTime(secs: Double): String = {
    s"${(secs / 3600).toInt}:${((secs % 3600) / 60).toInt}:${(secs % 60).toInt}"
  }
  override def toString: String =
    s"${formatTime(time)}|$tag|${person.getOrElse("na")}|${activity.getType}| => ${formatTime(serviceTime)}"
}

abstract class MSAgent(requests: List[MobilityServiceRequest]) {
  def getId: String
  override def toString: String = s"[$getId]"
}

case class MSAPerson(person: Person, requests: List[MobilityServiceRequest]) extends MSAgent(requests) {
  override def getId: String = person.getId.toString

  def getPickup: MobilityServiceRequest = {
    requests.head.tag match {
      case Pickup => requests.head
      case _      => requests(1)
    }
  }

  def getDropoff: MobilityServiceRequest = {
    requests.head.tag match {
      case Dropoff => requests.head
      case _       => requests(1)
    }
  }
}

case class MSAVehicle(vehicle: Vehicle, requests: List[MobilityServiceRequest]) extends MSAgent(requests) {
  override def getId: String = vehicle.getId.toString
  private val nbOfPassengers: Int = requests.count(_.tag == Dropoff)
  private val maxOccupancy
    : Int = vehicle.getType.getCapacity.getSeats // TODO to get information from elsewhere (see cav)
  def getFreeSeats: Int = maxOccupancy - nbOfPassengers
}

class MSMatch(taskQueue: List[MobilityServiceRequest]) extends DefaultEdge {
  override def toString: String = s"" + taskQueue.foreach(x => s"[${x.tag}]")
}

case class RVGraph(clazz: Class[MSMatch]) extends SimpleDirectedWeightedGraph[MSAgent, MSMatch](clazz)

class AlonsoMoraPoolingAlgForRideHail(
  demand: List[MSAPerson],
  supply: List[MSAVehicle],
  omega: Double,
  delta: Double,
  radius: Double,
  skimmer: BeamSkimmer
) {

  def getMetric(src: MobilityServiceRequest, dst: MobilityServiceRequest): TimeDistanceAndCost = {
    skimmer.getTimeDistanceAndCost(
      src.activity.getCoord,
      dst.activity.getCoord,
      src.time.toInt,
      BeamMode.CAR,
      BeamVehicleType.defaultCarBeamVehicleType.id
    )
  }

  def getPoolingRequestsList(requests: List[MobilityServiceRequest]): Option[List[MobilityServiceRequest]] = {
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val newPoolingList = MListBuffer(requests.head.copy())
    requests.drop(1).foldLeft(()) { case (_, curReq) =>
      val prevReq = newPoolingList.last
      val serviceTime = prevReq.serviceTime.toInt +
        getMetric(prevReq, curReq).timeAndCost.time.get
      val window: Double = curReq.tag match {
        case Pickup  => omega
        case Dropoff => delta
        case _       => 0
      }
      if (serviceTime <= curReq.time + window) {
        newPoolingList.append(curReq.copy(serviceTime = serviceTime))
      } else {
        return None
      }
    }
    Some(newPoolingList.toList)
  }

  def getPairwiseRVGraph: RVGraph = {
    val g = RVGraph(classOf[MSMatch])
    for (p1 <- demand;
         p2 <- demand) {
      if (p1 != p2) {
        val reqList = (p1.requests ++ p2.requests).sortWith(_.time < _.time)
        getPoolingRequestsList(reqList).map { x =>
          g.addVertex(p2)
          g.addVertex(p1)
          g.addEdge(p1, p2, new MSMatch(x))
        }
      }
    }
    for (v <- supply;
         p <- demand) {
      val metric = getMetric(v.requests.head, p.getPickup)
      if (metric.distance.get <= radius) {
        val reqList = (v.requests ++ p.requests).sortWith(_.time < _.time)
        getPoolingRequestsList(reqList).map { x =>
          g.addVertex(v)
          g.addVertex(p)
          g.addEdge(v, p, new MSMatch(x))
        }
      }
    }
    g
  }

}
