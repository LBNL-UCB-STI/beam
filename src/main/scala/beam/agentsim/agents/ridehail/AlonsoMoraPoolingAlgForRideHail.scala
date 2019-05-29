package beam.agentsim.agents.ridehail

import beam.agentsim.agents.planning.Trip
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, PersonIdWithActorRef}
import beam.agentsim.agents.{MobilityRequest, _}
import beam.router.BeamRouter.Location
import beam.router.BeamSkimmer.Skim
import beam.router.Modes.BeamMode
import beam.router.BeamSkimmer
import beam.sim.BeamServices
import beam.utils.NetworkHelper
import com.vividsolutions.jts.geom.Envelope
import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedWeightedGraph}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.population.PopulationUtils
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List

class AlonsoMoraPoolingAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  timeWindow: Map[MobilityRequestTrait, Int],
  maxRequestsPerVehicle: Int,
  beamServices: BeamServices
)(implicit val skimmer: BeamSkimmer) {

  // Request Vehicle Graph
  def pairwiseRVGraph: RVGraph = {
    val rvG = RVGraph(classOf[RideHailTrip])
    for {
      r1: CustomerRequest <- spatialDemand.values().asScala
      r2: CustomerRequest <- spatialDemand
        .getDisk(
          r1.pickup.activity.getCoord.getX,
          r1.pickup.activity.getCoord.getY,
          timeWindow(Pickup) * BeamSkimmer.speedMeterPerSec(BeamMode.CAV)
        )
        .asScala
        .withFilter(x => r1 != x && !rvG.containsEdge(r1, x))
    } yield {
      AlonsoMoraPoolingAlgForRideHail
        .getRidehailSchedule(timeWindow, List(r1.pickup, r1.dropoff, r2.pickup, r2.dropoff), beamServices)
        .map { schedule =>
          rvG.addVertex(r2)
          rvG.addVertex(r1)
          rvG.addEdge(r1, r2, RideHailTrip(List(r1, r2), schedule))
        }
    }

    for {
      v: VehicleAndSchedule <- supply.withFilter(_.getFreeSeats >= 1)
      r: CustomerRequest <- spatialDemand
        .getDisk(
          v.getLastDropoff.activity.getCoord.getX,
          v.getLastDropoff.activity.getCoord.getY,
          timeWindow(Pickup) * BeamSkimmer.speedMeterPerSec(BeamMode.CAV)
        )
        .asScala
        .take(maxRequestsPerVehicle)
    } yield {
      getRidehailSchedule(timeWindow, v.schedule ++ List(r.pickup, r.dropoff), beamServices).map { schedule =>
        rvG.addVertex(v)
        rvG.addVertex(r)
        rvG.addEdge(v, r, RideHailTrip(List(r), schedule))
      }
    }
    rvG
  }

  // Request Trip Vehicle Graph
  def rTVGraph(rvG: RVGraph, beamServices: BeamServices): RTVGraph = {
    val rTvG = RTVGraph(classOf[DefaultEdge])
    supply.withFilter(x => rvG.containsVertex(x)).foreach { v =>
      rTvG.addVertex(v)

      import scala.collection.mutable.{ListBuffer => MListBuffer}
      val individualRequestsList = MListBuffer.empty[RideHailTrip]
      for (t <- rvG.outgoingEdgesOf(v).asScala) {
        individualRequestsList.append(t)
        rTvG.addVertex(t)
        rTvG.addVertex(t.requests.head)
        rTvG.addEdge(t.requests.head, t)
        rTvG.addEdge(t, v)
      }

      if (v.getFreeSeats > 1) {
        val pairRequestsList = MListBuffer.empty[RideHailTrip]
        for {
          t1 <- individualRequestsList
          t2 <- individualRequestsList
            .drop(individualRequestsList.indexOf(t1))
            .withFilter(x => rvG.containsEdge(t1.requests.head, x.requests.head))
        } yield {
          getRidehailSchedule(
            timeWindow,
            v.schedule ++ (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
            beamServices
          ) map { schedule =>
            val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
            pairRequestsList append t
            rTvG.addVertex(t)
            rTvG.addEdge(t1.requests.head, t)
            rTvG.addEdge(t2.requests.head, t)
            rTvG.addEdge(t, v)
          }
        }

        val finalRequestsList: MListBuffer[RideHailTrip] = individualRequestsList ++ pairRequestsList
        for (k <- 3 until v.getFreeSeats + 1) {
          val kRequestsList = MListBuffer.empty[RideHailTrip]
          for {
            t1 <- finalRequestsList
            t2 <- finalRequestsList
              .drop(finalRequestsList.indexOf(t1))
              .withFilter(
                x => !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
              )
          } yield {
            getRidehailSchedule(
              timeWindow,
              v.schedule ++ (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
              beamServices
            ).map { schedule =>
              val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
              kRequestsList.append(t)
              rTvG.addVertex(t)
              t.requests.foldLeft(()) { case (_, r) => rTvG.addEdge(r, t) }
              rTvG.addEdge(t, v)
            }
          }

          finalRequestsList.appendAll(kRequestsList)
        }
      }
    }

    rTvG
  }

  // a greedy assignment using a cost function
  def greedyAssignment(rtvG: RTVGraph): List[(RideHailTrip, VehicleAndSchedule, Int)] = {
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    val C0: Int = timeWindow.foldLeft(0)(_ + _._2)
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val Rok = MListBuffer.empty[CustomerRequest]
    val Vok = MListBuffer.empty[VehicleAndSchedule]
    val greedyAssignmentList = MListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Int)]
    for (k <- V to 1 by -1) {
      rtvG
        .vertexSet()
        .asScala
        .filter(t => t.isInstanceOf[RideHailTrip] && t.asInstanceOf[RideHailTrip].requests.size == k)
        .map { t =>
          val trip = t.asInstanceOf[RideHailTrip]
          val vehicle = rtvG
            .getEdgeTarget(
              rtvG
                .outgoingEdgesOf(trip)
                .asScala
                .filter(e => rtvG.getEdgeTarget(e).isInstanceOf[VehicleAndSchedule])
                .head
            )
            .asInstanceOf[VehicleAndSchedule]
          val cost = trip.cost + C0 * rtvG
            .outgoingEdgesOf(trip)
            .asScala
            .filter(e => rtvG.getEdgeTarget(e).isInstanceOf[CustomerRequest])
            .count(y => !trip.requests.contains(y.asInstanceOf[CustomerRequest]))

          (trip, vehicle, cost)
        }
        .toList
        .sortBy(_._3)
        .foldLeft(()) {
          case (_, (trip, vehicle, cost)) =>
            if (!(trip.requests exists (r => Rok contains r)) &&
                !(Vok contains vehicle)) {
              Rok.appendAll(trip.requests)
              Vok.append(vehicle)
              greedyAssignmentList.append((trip, vehicle, cost))
            }
        }
    }
    greedyAssignmentList.toList
  }

}

object AlonsoMoraPoolingAlgForRideHail {

  // ************ Helper functions ************
  def getTimeDistanceAndCost(src: MobilityRequest, dst: MobilityRequest, beamServices: BeamServices)(
    implicit skimmer: BeamSkimmer
  ): Skim = {
    skimmer.getTimeDistanceAndCost(
      src.activity.getCoord,
      dst.activity.getCoord,
      src.time,
      BeamMode.CAR,
      Id.create("Car", classOf[BeamVehicleType])
    )
  }

  def getRidehailSchedule(
    timeWindow: Map[MobilityRequestTrait, Int],
    requests: List[MobilityRequest],
    beamServices: BeamServices
  )(
    implicit skimmer: BeamSkimmer
  ): Option[List[MobilityRequest]] = {
    val sortedRequests = requests.sortWith(_.time < _.time)
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val newPoolingList = MListBuffer(sortedRequests.head.copy())
    sortedRequests.drop(1).foldLeft(()) {
      case (_, curReq) =>
        val prevReq = newPoolingList.last
        val serviceTime = prevReq.serviceTime +
        getTimeDistanceAndCost(prevReq, curReq, beamServices).time.toInt
        if (serviceTime <= curReq.time + timeWindow(curReq.tag)) {
          newPoolingList.append(curReq.copy(serviceTime = serviceTime))
        } else {
          return None
        }
    }
    Some(newPoolingList.toList)
  }

  def createPersonRequest(vehiclePersonId: PersonIdWithActorRef, src: Location, srcTime: Int, dst: Location)(
    implicit skimmer: BeamSkimmer
  ): CustomerRequest = {
    val p1Act1: Activity = PopulationUtils.createActivityFromCoord(s"${vehiclePersonId.personId}Act1", src)
    p1Act1.setEndTime(srcTime)
    val p1Act2: Activity = PopulationUtils.createActivityFromCoord(s"${vehiclePersonId.personId}Act2", dst)
    val p1_tt: Int = skimmer
      .getTimeDistanceAndCost(
        p1Act1.getCoord,
        p1Act2.getCoord,
        0,
        BeamMode.CAR,
        Id.create("Car", classOf[BeamVehicleType])
      )
      .time
      .toInt
    CustomerRequest(
      vehiclePersonId,
      MobilityRequest(
        Some(vehiclePersonId),
        p1Act1,
        srcTime,
        Trip(p1Act1, None, null),
        BeamMode.RIDE_HAIL,
        Pickup,
        srcTime
      ),
      MobilityRequest(
        Some(vehiclePersonId),
        p1Act2,
        srcTime + p1_tt,
        Trip(p1Act2, None, null),
        BeamMode.RIDE_HAIL,
        Dropoff,
        srcTime + p1_tt
      ),
    )
  }

  def createVehicleAndSchedule(vid: String, vehicleType: BeamVehicleType, dst: Location, dstTime: Int): VehicleAndSchedule = {
    val v1 = new BeamVehicle(
      Id.create(vid, classOf[BeamVehicle]),
      new Powertrain(0.0),
      vehicleType
    )
    val v1Act0: Activity = PopulationUtils.createActivityFromCoord(s"${vid}Act0", dst)
    v1Act0.setEndTime(dstTime)
    VehicleAndSchedule(
      v1,
      List(
        MobilityRequest(
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

  // ***** Graph Structure *****
  sealed trait RTVGraphNode {
    def getId: String
    override def toString: String = s"[$getId]"
  }
  sealed trait RVGraphNode extends RTVGraphNode
  // customer requests
  case class CustomerRequest(person: PersonIdWithActorRef, pickup: MobilityRequest, dropoff: MobilityRequest)
      extends RVGraphNode {
    override def getId: String = person.personId.toString
  }
  // Ride Hail vehicles, capacity and their predefined schedule
  case class VehicleAndSchedule(vehicle: BeamVehicle, schedule: List[MobilityRequest]) extends RVGraphNode {
    private val nbOfPassengers: Int = schedule.count(_.tag == Dropoff)
    override def getId: String = vehicle.id.toString
    private val maxOccupancy: Int = vehicle.beamVehicleType.seatingCapacity
    def getFreeSeats: Int = maxOccupancy - nbOfPassengers
    def getLastDropoff: MobilityRequest = schedule.head
  }
  // Trip that can be satisfied by one or more ride hail vehicle
  case class RideHailTrip(requests: List[CustomerRequest], schedule: List[MobilityRequest])
      extends DefaultEdge
      with RTVGraphNode {
    override def getId: String = requests.foldLeft(s"trip:") { case (c, x) => c + s"$x -> " }
    val cost: Int = schedule.foldLeft(0) { case (c, r)                     => c + (r.serviceTime - r.time) }
  }
  case class RVGraph(clazz: Class[RideHailTrip])
      extends DefaultUndirectedWeightedGraph[RVGraphNode, RideHailTrip](clazz)
  case class RTVGraph(clazz: Class[DefaultEdge])
      extends DefaultUndirectedWeightedGraph[RTVGraphNode, DefaultEdge](clazz)
  // ***************************

}
