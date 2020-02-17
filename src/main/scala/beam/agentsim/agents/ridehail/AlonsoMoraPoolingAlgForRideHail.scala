package beam.agentsim.agents.ridehail

import beam.agentsim.agents.planning.Trip
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, PersonIdWithActorRef}
import beam.agentsim.agents.{MobilityRequest, _}
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.skim.{ODSkims, Skims, SkimsUtils}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager
import beam.sim.{BeamServices, Geofence}
import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedWeightedGraph}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.matsim.core.population.PopulationUtils
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer

class AlonsoMoraPoolingAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices
) {

  // Methods below should be kept as def (instead of val) to allow automatic value updating
  private def alonsoMora: AllocationManager.AlonsoMora =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora
  private def solutionSpaceSizePerVehicle: Int = alonsoMora.numRequestsPerVehicle
  private def waitingTimeInSec: Int = alonsoMora.waitingTimeInSec

  val rvG = RVGraph(classOf[RideHailTrip])
  val rTvG = RTVGraph(classOf[DefaultEdge])
  // Request Vehicle Graph
  private def pairwiseRVGraph: Unit = {
    for {
      r1: CustomerRequest <- spatialDemand.values().asScala
      r2: CustomerRequest <- spatialDemand
        .getDisk(
          r1.pickup.activity.getCoord.getX,
          r1.pickup.activity.getCoord.getY,
          waitingTimeInSec * SkimsUtils.speedMeterPerSec(BeamMode.CAV)
        )
        .asScala
        .withFilter(x => r1 != x && !rvG.containsEdge(r1, x))
    } yield {
      getRidehailSchedule(
        List.empty[MobilityRequest],
        List(r1.pickup, r1.dropoff, r2.pickup, r2.dropoff),
        Integer.MAX_VALUE,
        beamServices
      ).map { schedule =>
        rvG.addVertex(r2)
        rvG.addVertex(r1)
        rvG.addEdge(r1, r2, RideHailTrip(List(r1, r2), schedule))
      }
    }

    for {
      v: VehicleAndSchedule <- supply.withFilter(_.getFreeSeats >= 1)
      r: CustomerRequest <- spatialDemand
        .getDisk(
          v.getRequestWithCurrentVehiclePosition.activity.getCoord.getX,
          v.getRequestWithCurrentVehiclePosition.activity.getCoord.getY,
          waitingTimeInSec * SkimsUtils.speedMeterPerSec(BeamMode.CAV)
        )
        .asScala
        .take(solutionSpaceSizePerVehicle)
    } yield {
      getRidehailSchedule(v.schedule, List(r.pickup, r.dropoff), v.vehicleRemainingRangeInMeters.toInt, beamServices)
        .map { schedule =>
          rvG.addVertex(v)
          rvG.addVertex(r)
          rvG.addEdge(v, r, RideHailTrip(List(r), schedule))
        }
    }
  }

  // Request Trip Vehicle Graph
  private def rTVGraph(): Unit = {
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
            v.schedule,
            (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
            v.vehicleRemainingRangeInMeters.toInt,
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
              v.schedule,
              (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
              v.vehicleRemainingRangeInMeters.toInt,
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
  }

  // a greedy assignment using a cost function
  def matchAndAssign(tick: Int): List[(RideHailTrip, VehicleAndSchedule, Double)] = {
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    greedyAssignment(rTvG, V, solutionSpaceSizePerVehicle)
  }

}

object AlonsoMoraPoolingAlgForRideHail {

  def checkDistance(r: MobilityRequest, schedule: List[MobilityRequest], searchRadius: Double): Boolean = {
    schedule.foreach { s =>
      if (GeoUtils.distFormula(r.activity.getCoord, s.activity.getCoord) <= searchRadius)
        return true
    }
    false
  }

  // a greedy assignment using a cost function
  def greedyAssignment(
    rTvG: RTVGraph,
    maximumVehCapacity: Int,
    solutionSpaceSizePerVehicle: Int
  ): List[(RideHailTrip, VehicleAndSchedule, Double)] = {
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val Rok = collection.mutable.HashSet.empty[CustomerRequest]
    val Vok = collection.mutable.HashSet.empty[VehicleAndSchedule]
    val greedyAssignmentList = MListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Double)]
    for (k <- maximumVehCapacity to 1 by -1) {
      rTvG
        .vertexSet()
        .asScala
        .filter(t => t.isInstanceOf[RideHailTrip] && t.asInstanceOf[RideHailTrip].requests.size == k)
        .map { t =>
          val trip = t.asInstanceOf[RideHailTrip]
          val vehicle = rTvG
            .getEdgeTarget(
              rTvG
                .outgoingEdgesOf(trip)
                .asScala
                .filter(e => rTvG.getEdgeTarget(e).isInstanceOf[VehicleAndSchedule])
                .head
            )
            .asInstanceOf[VehicleAndSchedule]
          val cost = trip.requests.size * trip.sumOfDelaysAsFraction + (solutionSpaceSizePerVehicle - trip.requests.size) * 1.0
          (trip, vehicle, cost)
        }
        .toList
        .sortBy(_._3)
        .foreach {
          case (trip, vehicle, cost) if !(Vok contains vehicle) && !(trip.requests exists (r => Rok contains r)) =>
            trip.requests.foreach(Rok.add)
            Vok.add(vehicle)
            greedyAssignmentList.append((trip, vehicle, cost))
          case _ =>
        }
    }
    greedyAssignmentList.toList
  }

  // ************ Helper functions ************
  def getTimeDistanceAndCost(src: MobilityRequest, dst: MobilityRequest, beamServices: BeamServices) = {
    Skims.od_skimmer.getTimeDistanceAndCost(
      src.activity.getCoord,
      dst.activity.getCoord,
      src.baselineNonPooledTime,
      BeamMode.CAR,
      Id.create(
        beamServices.beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
        classOf[BeamVehicleType]
      ),
      beamServices
    )
  }

  def getRidehailSchedule(
    schedule: List[MobilityRequest],
    newRequests: List[MobilityRequest],
    remainingVehicleRangeInMeters: Int,
    beamServices: BeamServices
  ): Option[List[MobilityRequest]] = {
    val newPoolingList = scala.collection.mutable.ListBuffer.empty[MobilityRequest]
    val reversedSchedule = schedule.reverse
    val sortedRequests = reversedSchedule.lastOption match {
      case Some(_) if reversedSchedule.exists(_.tag == EnRoute) =>
        val enRouteIndex = reversedSchedule.indexWhere(_.tag == EnRoute) + 1
        newPoolingList.appendAll(reversedSchedule.slice(0, enRouteIndex))
        // We make sure that request time is always equal or greater than the driver's "current tick" as denoted by time in EnRoute
        val shiftRequestsBy =
          Math.max(0, reversedSchedule(enRouteIndex - 1).baselineNonPooledTime - newRequests.head.baselineNonPooledTime)
        (reversedSchedule.slice(enRouteIndex, reversedSchedule.size) ++ newRequests.map(
          req =>
            req.copy(
              baselineNonPooledTime = req.baselineNonPooledTime + shiftRequestsBy,
              serviceTime = req.serviceTime + shiftRequestsBy
          )
        )).sortBy(
          mr => (mr.baselineNonPooledTime, mr.person.map(_.personId.toString).getOrElse("ZZZZZZZZZZZZZZZZZZZZZZZ"))
        )
      case Some(_) =>
        newPoolingList.appendAll(reversedSchedule)
        newRequests.sortBy(_.baselineNonPooledTime)
      case None =>
        val temp = newRequests.sortBy(_.baselineNonPooledTime)
        newPoolingList.append(temp.head)
        temp.drop(1)
    }
    sortedRequests.foreach { curReq =>
      val prevReq = newPoolingList.lastOption.getOrElse(newPoolingList.last)
      val tdc = getTimeDistanceAndCost(prevReq, curReq, beamServices)
      val serviceTime = prevReq.serviceTime + tdc.time
      val serviceDistance = prevReq.serviceDistance + tdc.distance.toInt
      if (serviceTime <= curReq.upperBoundTime && serviceDistance <= remainingVehicleRangeInMeters) {
        newPoolingList.append(curReq.copy(serviceTime = serviceTime, serviceDistance = serviceDistance))
      } else {
        return None
      }
    }
    Some(newPoolingList.toList)
  }

  def createPersonRequest(
    vehiclePersonId: PersonIdWithActorRef,
    src: Location,
    departureTime: Int,
    dst: Location,
    beamServices: BeamServices
  ): CustomerRequest = {
    val alonsoMora: AllocationManager.AlonsoMora =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora
    val waitingTimeInSec = alonsoMora.waitingTimeInSec
    val travelTimeDelayAsFraction = alonsoMora.excessRideTimeAsFraction

    val p1Act1: Activity = PopulationUtils.createActivityFromCoord(s"${vehiclePersonId.personId}Act1", src)
    p1Act1.setEndTime(departureTime)
    val p1Act2: Activity = PopulationUtils.createActivityFromCoord(s"${vehiclePersonId.personId}Act2", dst)
    val skim = Skims.od_skimmer
      .getTimeDistanceAndCost(
        p1Act1.getCoord,
        p1Act2.getCoord,
        departureTime,
        BeamMode.CAR,
        Id.create(
          beamServices.beamScenario.beamConfig.beam.agentsim.agents.rideHail.initialization.procedural.vehicleTypeId,
          classOf[BeamVehicleType]
        ),
        beamServices
      )
    CustomerRequest(
      vehiclePersonId,
      MobilityRequest(
        Some(vehiclePersonId),
        p1Act1,
        departureTime,
        Trip(p1Act1, None, null),
        BeamMode.RIDE_HAIL,
        Pickup,
        departureTime,
        departureTime + waitingTimeInSec,
        0
      ),
      MobilityRequest(
        Some(vehiclePersonId),
        p1Act2,
        departureTime + skim.time,
        Trip(p1Act2, None, null),
        BeamMode.RIDE_HAIL,
        Dropoff,
        departureTime + skim.time,
        Math.round(departureTime + skim.time + waitingTimeInSec + travelTimeDelayAsFraction * skim.time).toInt,
        skim.distance.toInt
      )
    )
  }

  def createVehicleAndScheduleFromRideHailAgentLocation(
    veh: RideHailAgentLocation,
    tick: Int,
    beamServices: BeamServices,
    remainingRangeInMeters: Double
  ): VehicleAndSchedule = {
    val v1 = new BeamVehicle(
      Id.create(veh.vehicleId, classOf[BeamVehicle]),
      new Powertrain(0.0),
      veh.vehicleType
    )
    val vehCurrentLocation =
      veh.currentPassengerSchedule.map(_.locationAtTime(tick, beamServices)).getOrElse(veh.currentLocationUTM.loc)
    val v1Act0: Activity = PopulationUtils.createActivityFromCoord(s"${veh.vehicleId}Act0", vehCurrentLocation)
    v1Act0.setEndTime(tick)
    var alonsoSchedule: ListBuffer[MobilityRequest] = ListBuffer()

    val alonsoMora: AllocationManager.AlonsoMora =
      beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora
    val waitingTimeInSec = alonsoMora.waitingTimeInSec
    val travelTimeDelayAsFraction = alonsoMora.excessRideTimeAsFraction

    veh.currentPassengerSchedule.foreach {
      _.schedule.foreach {
        case (leg, manifest) =>
          if (manifest.riders.isEmpty) {
            val theActivity = PopulationUtils.createActivityFromCoord(
              s"${veh.vehicleId}Act0",
              beamServices.geo.wgs2Utm(leg.travelPath.startPoint.loc)
            )
            alonsoSchedule = ListBuffer(
              MobilityRequest(
                None,
                theActivity,
                leg.startTime,
                Trip(theActivity, None, null),
                BeamMode.RIDE_HAIL,
                Relocation,
                leg.startTime,
                leg.startTime + Int.MaxValue - 30000000,
                0
              )
            )
          } else {
            val thePickups = manifest.boarders.map { boarder =>
              val theActivity = PopulationUtils.createActivityFromCoord(
                s"${veh.vehicleId}Act0",
                beamServices.geo.wgs2Utm(leg.travelPath.startPoint.loc)
              )
              theActivity.setEndTime(leg.startTime)
              MobilityRequest(
                Some(boarder),
                theActivity,
                leg.startTime,
                Trip(theActivity, None, null),
                BeamMode.RIDE_HAIL,
                Pickup,
                leg.startTime,
                leg.startTime + waitingTimeInSec,
                0
              )
            }
            val theDropoffs = manifest.alighters.map { alighter =>
              val theActivity = PopulationUtils.createActivityFromCoord(
                s"${veh.vehicleId}Act0",
                beamServices.geo.wgs2Utm(leg.travelPath.endPoint.loc)
              )
              theActivity.setEndTime(leg.endTime)
              MobilityRequest(
                Some(alighter),
                theActivity,
                leg.endTime,
                Trip(theActivity, None, null),
                BeamMode.RIDE_HAIL,
                Dropoff,
                leg.endTime,
                Math
                  .round(leg.endTime + waitingTimeInSec + (leg.endTime - leg.startTime) * travelTimeDelayAsFraction)
                  .toInt,
                leg.travelPath.distanceInM.toInt
              )
            }
            alonsoSchedule ++= thePickups ++ theDropoffs
          }
      }
    }
    if (alonsoSchedule.isEmpty) {
      alonsoSchedule += MobilityRequest(
        None,
        v1Act0,
        tick,
        Trip(v1Act0, None, null),
        BeamMode.RIDE_HAIL,
        Dropoff,
        tick,
        tick,
        0
      )
    } else {
      alonsoSchedule += MobilityRequest(
        None,
        v1Act0,
        tick + 1,
        Trip(v1Act0, None, null),
        BeamMode.RIDE_HAIL,
        EnRoute,
        tick,
        tick + Int.MaxValue - 30000000,
        0
      )
    }
    val res = VehicleAndSchedule(
      v1,
      alonsoSchedule
        .sortBy(mr => (mr.baselineNonPooledTime, mr.person.map(_.personId.toString).getOrElse("")))
        .reverse
        .toList,
      veh.geofence,
      veh.vehicleType.seatingCapacity,
      remainingRangeInMeters
    )
    res
  }

  def createVehicleAndSchedule(
    vid: String,
    vehicleType: BeamVehicleType,
    dst: Location,
    dstTime: Int,
    geofence: Option[Geofence] = None,
    seatsAvailable: Int
  ): VehicleAndSchedule = {
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
          dstTime,
          dstTime,
          0
        )
      ),
      geofence,
      seatsAvailable
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
    override def toString: String = s"Person:${person.personId}|Pickup:$pickup|Dropoff:$dropoff"
  }
  // Ride Hail vehicles, capacity and their predefined schedule
  case class VehicleAndSchedule(
    vehicle: BeamVehicle,
    schedule: List[MobilityRequest],
    geofence: Option[Geofence],
    seatsAvailable: Int,
    vehicleRemainingRangeInMeters: Double = Double.MaxValue
  ) extends RVGraphNode {
    private val numberOfPassengers: Int =
      schedule.takeWhile(_.tag != EnRoute).count(req => req.person.isDefined && req.tag == Dropoff)
    override def getId: String = vehicle.id.toString
    def getNoPassengers: Int = numberOfPassengers
    def getFreeSeats: Int = seatsAvailable - numberOfPassengers
    def getRequestWithCurrentVehiclePosition: MobilityRequest = schedule.find(_.tag == EnRoute).getOrElse(schedule.head)
  }
  // Trip that can be satisfied by one or more ride hail vehicle
  case class RideHailTrip(requests: List[CustomerRequest], schedule: List[MobilityRequest])
      extends DefaultEdge
      with RTVGraphNode {
    override def getId: String = requests.foldLeft(s"trip:") { case (c, x) => c + s"$x -> " }
    val sumOfDelays: Int = schedule.foldLeft(0) { case (c, r)              => c + (r.serviceTime - r.baselineNonPooledTime) }

    val sumOfDelaysAsFraction: Int = sumOfDelays / schedule.foldLeft(0) {
      case (c, r) => c + (r.upperBoundTime - r.baselineNonPooledTime)
    }
    override def toString: String =
      s"${requests.size} requests and this schedule: ${schedule.map(_.toString).mkString("\n")}"
  }
  case class RVGraph(clazz: Class[RideHailTrip])
      extends DefaultUndirectedWeightedGraph[RVGraphNode, RideHailTrip](clazz)
  case class RTVGraph(clazz: Class[DefaultEdge])
      extends DefaultUndirectedWeightedGraph[RTVGraphNode, DefaultEdge](clazz)
  // ***************************

}
