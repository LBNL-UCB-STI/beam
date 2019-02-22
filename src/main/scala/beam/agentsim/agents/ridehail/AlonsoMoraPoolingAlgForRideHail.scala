package beam.agentsim.agents.ridehail

import beam.agentsim.agents.planning.Trip
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehiclePersonId}
import beam.router.BeamRouter.Location
import beam.router.{BeamSkimmer, TimeDistanceAndCost}
import beam.router.Modes.BeamMode
import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedWeightedGraph}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils

import scala.collection.immutable.List
import scala.collection.JavaConverters._

// *** Algorithm ***
class AlonsoMoraPoolingAlgForRideHail(
  demand: List[CustomerRequest],
  supply: List[VehicleAndSchedule],
  omega: Int,
  delta: Int,
  radius: Int,
  implicit val skimmer: BeamSkimmer
) {

  val timeWindow: Map[MobilityServiceRequestType, Int] = Map((Pickup, omega), (Dropoff, delta))

  // Request Vehicle Graph
  def pairwiseRVGraph: RVGraph = {
    val rvG = RVGraph(classOf[RideHailTrip])
    for (r1 <- demand;
         r2 <- demand) {
      if (r1 != r2 && !rvG.containsEdge(r1, r2)) {
        AlonsoMoraPoolingAlgForRideHail
          .getRidehailSchedule(timeWindow, List(r1.pickup, r1.dropoff, r2.pickup, r2.dropoff))
          .map { schedule =>
            rvG.addVertex(r2)
            rvG.addVertex(r1)
            rvG.addEdge(r1, r2, RideHailTrip(List(r1, r2), schedule))
          }
      }
    }
    for (v <- supply;
         r <- demand) {
      val distance = getTimeDistanceAndCost(v.schedule.head, r.pickup).distance.get
      if (!rvG.containsEdge(v, r) && distance <= radius && v.getFreeSeats >= 1) {
        getRidehailSchedule(timeWindow, v.schedule ++ List(r.pickup, r.dropoff)).map { schedule =>
          rvG.addVertex(v)
          rvG.addVertex(r)
          rvG.addEdge(v, r, RideHailTrip(List(r), schedule))
        }
      }
    }
    rvG
  }

  // Request Trip Vehicle Graph
  // can be parallelized by vehicle
  def rTVGraph(rvG: RVGraph): RTVGraph = {
    val rTvG = RTVGraph(classOf[DefaultEdge])
    for (v <- supply) {

      if (v.getFreeSeats > 0 && rvG.containsVertex(v)) {
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
          var index = 1
          for (t1 <- individualRequestsList) {
            for (t2 <- individualRequestsList
                   .drop(index)
                   .withFilter(x => rvG.containsEdge(t1.requests.head, x.requests.head))) {
              getRidehailSchedule(
                timeWindow,
                v.schedule ++ (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff))
              ).map { schedule =>
                val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                pairRequestsList.append(t)
                rTvG.addVertex(t)
                rTvG.addEdge(t1.requests.head, t)
                rTvG.addEdge(t2.requests.head, t)
                rTvG.addEdge(t, v)
              }
            }
            index += 1
          }

          val finalRequestsList: MListBuffer[RideHailTrip] = individualRequestsList ++ pairRequestsList
          for (k <- 3 until v.getFreeSeats + 1) {
            var index = 1
            val kRequestsList = MListBuffer.empty[RideHailTrip]
            for (t1 <- finalRequestsList) {
              for (t2 <- finalRequestsList
                     .drop(index)
                     .withFilter(
                       x =>
                         !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
                     )) {
                getRidehailSchedule(
                  timeWindow,
                  v.schedule ++ (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff))
                ).map { schedule =>
                  val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                  kRequestsList.append(t)
                  rTvG.addVertex(t)
                  t.requests.foldLeft(()) { case (_, r) => rTvG.addEdge(r, t) }
                  rTvG.addEdge(t, v)
                }
              }
              index += 1
            }
            finalRequestsList.appendAll(kRequestsList)
          }
        }
      }
    }
    rTvG
  }

  // a greedy assignment using a cost function
  def greedyAssignment(rtvG: RTVGraph): List[(RideHailTrip, VehicleAndSchedule, Int)] = {
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    val C0: Int = omega + delta
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val Rok = MListBuffer.empty[CustomerRequest]
    val Vok = MListBuffer.empty[VehicleAndSchedule]
    val greedyAssignmentList = MListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Int)]
    for (k <- V to 1 by -1) {
      rtvG
        .vertexSet()
        .asScala
        .filter(t => t.isInstanceOf[RideHailTrip] && t.asInstanceOf[RideHailTrip].requests.size == k)
        .map { trip =>
          (
            trip.asInstanceOf[RideHailTrip],
            rtvG
              .getEdgeTarget(
                rtvG
                  .outgoingEdgesOf(trip)
                  .asScala
                  .filter(e => rtvG.getEdgeTarget(e).isInstanceOf[VehicleAndSchedule])
                  .head
              )
              .asInstanceOf[VehicleAndSchedule],
            trip.asInstanceOf[RideHailTrip].cost + demand.count(
              y => !(trip.asInstanceOf[RideHailTrip].requests map (_.person) contains y.person)
            ) * C0 / k
          )
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
  // ******************************
  // Helper functions {} Start
  def getTimeDistanceAndCost(src: MobilityServiceRequest, dst: MobilityServiceRequest)(
    implicit skimmer: BeamSkimmer
  ): TimeDistanceAndCost = {
    skimmer.getTimeDistanceAndCost(
      src.activity.getCoord,
      dst.activity.getCoord,
      src.time,
      BeamMode.CAR,
      BeamVehicleType.defaultCarBeamVehicleType.id
    )
  }

  def getRidehailSchedule(timeWindow: Map[MobilityServiceRequestType, Int], requests: List[MobilityServiceRequest])(
    implicit skimmer: BeamSkimmer
  ): Option[List[MobilityServiceRequest]] = {
    val sortedRequests = requests.sortWith(_.time < _.time)
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val newPoolingList = MListBuffer(sortedRequests.head.copy())
    sortedRequests.drop(1).foldLeft(()) {
      case (_, curReq) =>
        val prevReq = newPoolingList.last
        val serviceTime = prevReq.serviceTime +
        getTimeDistanceAndCost(prevReq, curReq).timeAndCost.time.get
        if (serviceTime <= curReq.time + timeWindow(curReq.tag)) {
          newPoolingList.append(curReq.copy(serviceTime = serviceTime))
        } else {
          return None
        }
    }
    Some(newPoolingList.toList)
  }

  // Helper functions {} End
  // ******************************
  def newVehicle(id: String): BeamVehicle = {
    new BeamVehicle(Id.create(id, classOf[BeamVehicle]), new Powertrain(0.0), BeamVehicleType.defaultCarBeamVehicleType)
  }

  def newPerson(id: String): Person = {
    PopulationUtils.createPopulation(ConfigUtils.createConfig).getFactory.createPerson(Id.createPersonId(id))
  }

  def seconds(h: Int, m: Int, s: Int = 0): Int = h * 3600 + m * 60 + s

  def createPersonRequest(vehiclePersonId: VehiclePersonId, src: Location, srcTime: Int, dst: Location)(
    implicit skimmer: BeamSkimmer
  ): CustomerRequest = {
    val p1 = newPerson(vehiclePersonId.personId.toString)
    val p1Act1: Activity = PopulationUtils.createActivityFromCoord(s"${vehiclePersonId.personId}Act1", src)
    p1Act1.setEndTime(srcTime)
    val p1Act2: Activity = PopulationUtils.createActivityFromCoord(s"${vehiclePersonId.personId}Act2", dst)
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
      vehiclePersonId,
      MobilityServiceRequest(
        Some(vehiclePersonId),
        p1Act1,
        srcTime,
        Trip(p1Act1, None, null),
        BeamMode.RIDE_HAIL,
        Pickup,
        srcTime
      ),
      MobilityServiceRequest(
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

  def createVehicleAndSchedule(vid: String, dst: Location, dstTime: Int): VehicleAndSchedule = {
    val v1 = newVehicle(vid)
    val v1Act0: Activity = PopulationUtils.createActivityFromCoord(s"${vid}Act0", dst)
    v1Act0.setEndTime(dstTime)
    VehicleAndSchedule(
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

  // ***** Graph Structure *****
  sealed trait RTVGraphNode {
    def getId: String
    override def toString: String = s"[$getId]"
  }
  sealed trait RVGraphNode extends RTVGraphNode
  // customer requests
  case class CustomerRequest(person: VehiclePersonId, pickup: MobilityServiceRequest, dropoff: MobilityServiceRequest)
      extends RVGraphNode {
    override def getId: String = person.personId.toString
  }
  // Ride Hail vehicles, capacity and their predefined schedule
  case class VehicleAndSchedule(vehicle: BeamVehicle, schedule: List[MobilityServiceRequest]) extends RVGraphNode {
    private val nbOfPassengers: Int = schedule.count(_.tag == Dropoff)
    override def getId: String = vehicle.id.toString
    private val maxOccupancy: Int = vehicle.beamVehicleType.seatingCapacity
    def getFreeSeats: Int = maxOccupancy - nbOfPassengers

  }
  // Trip that can be satisfied by one or more ride hail vehicle
  case class RideHailTrip(requests: List[CustomerRequest], schedule: List[MobilityServiceRequest])
      extends DefaultEdge
      with RTVGraphNode {
    override def getId: String = requests.foldLeft(s"trip:") { case (c, x) => c + s"$x -> " }
    val cost: Int = schedule.foldLeft(0) { case (c, r)                     => c + (r.serviceTime - r.time) }
  }

  case class RVGraph(clazz: Class[RideHailTrip])
      extends DefaultUndirectedWeightedGraph[RVGraphNode, RideHailTrip](clazz)
  case class RTVGraph(clazz: Class[DefaultEdge])
      extends DefaultUndirectedWeightedGraph[RTVGraphNode, DefaultEdge](clazz)

  // ***** CAV structure ****
  sealed trait MobilityServiceRequestType
  case object Pickup extends MobilityServiceRequestType
  case object Dropoff extends MobilityServiceRequestType
  case object Relocation extends MobilityServiceRequestType
  case object Init extends MobilityServiceRequestType

  case class MobilityServiceRequest(
    person: Option[VehiclePersonId],
    activity: Activity,
    time: Int,
    trip: Trip,
    defaultMode: BeamMode,
    tag: MobilityServiceRequestType,
    serviceTime: Int,
    routingRequestId: Option[Int] = None
  ) {
    val nextActivity = Some(trip.activity)

    def formatTime(secs: Double): String = {
      s"${secs / 3600}:${(secs % 3600) / 60}:${secs % 60}"
    }
    override def toString: String =
      s"${formatTime(time)}|$tag|${person.getOrElse("na")}|${activity.getType}| => ${formatTime(serviceTime)}"
  }
}
