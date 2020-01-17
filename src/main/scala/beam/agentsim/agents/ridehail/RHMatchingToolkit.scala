package beam.agentsim.agents.ridehail

import beam.agentsim.agents._
import beam.agentsim.agents.planning.Trip
import beam.agentsim.agents.ridehail.RideHailVehicleManager.RideHailAgentLocation
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, PersonIdWithActorRef}
import beam.router.BeamRouter.Location
import beam.router.Modes.BeamMode
import beam.router.skim.Skims
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.RideHail.AllocationManager
import beam.sim.{BeamServices, Geofence}
import com.typesafe.scalalogging.LazyLogging
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory}
import org.geotools.referencing.GeodeticCalculator
import org.geotools.referencing.crs.DefaultGeographicCRS
import org.jgrapht.graph.{DefaultEdge, DefaultUndirectedWeightedGraph}
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils

import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future
import scala.util.control.Breaks._

object RHMatchingToolkit {

  trait RHMatchingAlgorithm extends LazyLogging {
    def matchAndAssign(tick: Int): Future[List[RideHailTrip]]
  }

  // ***** Graph Structure *****
  sealed trait RTVGraphNode {
    def getId: String
    override def toString: String = s"[$getId]"
  }
  sealed trait RVGraphNode extends RTVGraphNode
  case class RVGraph(clazz: Class[RideHailTrip])
      extends DefaultUndirectedWeightedGraph[RVGraphNode, RideHailTrip](clazz)
  case class RTVGraph(clazz: Class[DefaultEdge])
      extends DefaultUndirectedWeightedGraph[RTVGraphNode, DefaultEdge](clazz)
  // ***************************

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
    vehicleRemainingRangeInMeters: Double = Double.MaxValue
  ) extends RVGraphNode {
    private val numberOfPassengers: Int =
      schedule.takeWhile(_.tag != EnRoute).count(req => req.person.isDefined && req.tag == Dropoff)
    private val seatingCapacity: Int = vehicle.beamVehicleType.seatingCapacity
    override def getId: String = vehicle.id.toString
    def getNoPassengers: Int = numberOfPassengers
    def getSeatingCapacity: Int = seatingCapacity
    def getFreeSeats: Int = seatingCapacity - numberOfPassengers
    def getRequestWithCurrentVehiclePosition: MobilityRequest = schedule.find(_.tag == EnRoute).getOrElse(schedule.head)
  }
  // Trip that can be satisfied by one or more ride hail vehicle
  case class RideHailTrip(
    requests: List[CustomerRequest],
    schedule: List[MobilityRequest],
    vehicle: Option[VehicleAndSchedule]
  ) extends DefaultEdge
      with RTVGraphNode {
    val sumOfDelays: Int = schedule.filter(_.isDropoff).map(s => s.serviceTime - s.baselineNonPooledTime).sum
    val upperBoundDelays: Int = schedule.filter(_.isDropoff).map(s => s.upperBoundTime - s.baselineNonPooledTime).sum
    def getId: String = s"${vehicle.toString}:${requests.sortBy(_.getId).map(_.getId).mkString(",")}"
    override def toString: String =
      s"${requests.size} requests and this schedule: ${schedule.map(_.toString).mkString("\n")}"
  }

  def checkAngle(origin: Coord, dest1: Coord, dest2: Coord)(implicit services: BeamServices): Boolean = {
    val crs = DefaultGeographicCRS.WGS84
    val orgWgs = services.geo.utm2Wgs.transform(origin)
    val dst1Wgs = services.geo.utm2Wgs.transform(dest1)
    val dst2Wgs = services.geo.utm2Wgs.transform(dest2)
    val calc = new GeodeticCalculator(crs)
    val gf = new GeometryFactory()
    val point1 = gf.createPoint(new Coordinate(orgWgs.getX, orgWgs.getY))
    calc.setStartingGeographicPoint(point1.getX, point1.getY)
    val point2 = gf.createPoint(new Coordinate(dst1Wgs.getX, dst1Wgs.getY))
    calc.setDestinationGeographicPoint(point2.getX, point2.getY)
    val azimuth1 = calc.getAzimuth
    val point3 = gf.createPoint(new Coordinate(dst2Wgs.getX, dst2Wgs.getY))
    calc.setDestinationGeographicPoint(point3.getX, point3.getY)
    val azimuth2 = calc.getAzimuth
    val degrees = azimuth2 - azimuth1
    Math.abs(degrees) < 45.0
  }

  def checkDistance(r: MobilityRequest, schedule: List[MobilityRequest], searchRadius: Double): Boolean = {
    schedule.exists(s => GeoUtils.distFormula(r.activity.getCoord, s.activity.getCoord) <= searchRadius)
  }

  def getRequestsWithinGeofence(v: VehicleAndSchedule, demand: List[CustomerRequest]) = {
    // get all customer requests located at a proximity to the vehicle
    v.geofence match {
      case Some(gf) =>
        val gfCenter = new Coord(gf.geofenceX, gf.geofenceY)
        demand.filter(
          r =>
            GeoUtils.distFormula(r.pickup.activity.getCoord, gfCenter) <= gf.geofenceRadius &&
            GeoUtils.distFormula(r.dropoff.activity.getCoord, gfCenter) <= gf.geofenceRadius
        )
      case _ => demand
    }
  }

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

  def getNearbyRequestsHeadingSameDirection(v: VehicleAndSchedule, demand: List[CustomerRequest], searchSpace: Int)(
    implicit services: BeamServices
  ): List[CustomerRequest] = {
    val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
    val center = requestWithCurrentVehiclePosition.activity.getCoord

    if (requestWithCurrentVehiclePosition.tag == EnRoute) {
      // if vehicle is EnRoute, then filter list of customer based on the destination of the passengers
      val i = v.schedule.indexWhere(_.tag == EnRoute)
      val mainTasks = v.schedule.slice(0, i)
      demand.filter(
        r =>
          mainTasks
            .filter(_.pickupRequest.isDefined)
            .exists(m => RHMatchingToolkit.checkAngle(center, m.activity.getCoord, r.dropoff.activity.getCoord))
      )
    } else {
      // if vehicle is empty, prioritize the destination of the current closest customers
      val customers = demand.sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
      val mainRequests = customers.slice(0, Math.min(customers.size, searchSpace))
      mainRequests
        .map(
          r1 =>
            r1 +: customers.filter(
              r2 =>
                r1 != r2 && RHMatchingToolkit
                  .checkAngle(center, r1.dropoff.activity.getCoord, r2.dropoff.activity.getCoord)
          )
        )
        .sortBy(-_.size)
        .flatten
        .distinct
    }
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

    var isValid = true
    breakable {
      for (curReq <- sortedRequests) {
        val prevReq = newPoolingList.lastOption.getOrElse(newPoolingList.last)
        val tdc = getTimeDistanceAndCost(prevReq, curReq, beamServices)
        val serviceTime = prevReq.serviceTime + tdc.time
        val serviceDistance = prevReq.serviceDistance + tdc.distance.toInt
        if (serviceTime <= curReq.upperBoundTime && serviceDistance <= remainingVehicleRangeInMeters) {
          newPoolingList.append(curReq.copy(serviceTime = serviceTime, serviceDistance = serviceDistance))
        } else {
          isValid = false
          break
        }
      }
    }
    if (isValid) Some(newPoolingList.toList) else None
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
    vehicleRemainingRangeInMeters: Int = Int.MaxValue
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
      vehicleRemainingRangeInMeters
    )

  }

}
