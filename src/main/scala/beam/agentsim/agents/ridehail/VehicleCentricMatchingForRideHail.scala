package beam.agentsim.agents.ridehail

import beam.agentsim.agents.EnRoute
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class VehicleCentricMatchingForRideHail(
  demand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  services: BeamServices,
  skimmer: BeamSkimmer
) {
  private val solutionSpaceSizePerVehicle =
    services.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.solutionSpaceSizePerVehicle
  private val waitingTimeInSec =
    services.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec
  private val searchRadius = waitingTimeInSec * BeamSkimmer.speedMeterPerSec(BeamMode.CAV)

  type AssignmentKey = (RideHailTrip, VehicleAndSchedule, Double)

  def matchAndAssign(tick: Int): Future[List[AssignmentKey]] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { vehicleCentricMatching(v) }
      })
      .map(result => greedyAssignment(result.flatten))
      .recover {
        case e =>
          println(e.getMessage)
          List.empty[AssignmentKey]
      }
  }

  private def vehicleCentricMatching(
    v: VehicleAndSchedule
  ): List[AssignmentKey] = {
    val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
    val center = requestWithCurrentVehiclePosition.activity.getCoord

    // get all customer requests located at a proximity to the vehicle
    var customers = v.geofence match {
      case Some(gf) =>
        val gfCenter = new Coord(gf.geofenceX, gf.geofenceY)
        demand
          .getDisk(center.getX, center.getY, searchRadius)
          .asScala
          .filter(
            r =>
              GeoUtils.distFormula(r.pickup.activity.getCoord, gfCenter) <= gf.geofenceRadius &&
              GeoUtils.distFormula(r.dropoff.activity.getCoord, gfCenter) <= gf.geofenceRadius
          )
          .toList
      case _ =>
        demand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
    }

    // if no customer found, returns
    if (customers.isEmpty)
      return List.empty[AssignmentKey]

    if (requestWithCurrentVehiclePosition.tag == EnRoute) {
      // if vehicle is EnRoute, then filter list of customer based on the destination of the passengers
      val i = v.schedule.indexWhere(_.tag == EnRoute)
      val mainTasks = v.schedule.slice(0, i)
      customers = customers
        .filter(r => AlonsoMoraPoolingAlgForRideHail.checkDistance(r.dropoff, mainTasks, searchRadius))
        .sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
    } else {
      // if vehicle is empty, prioritize the destination of the current closest customers
      customers = customers.sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
      val mainRequests = customers.slice(0, Math.max(customers.size, 1))
      customers = mainRequests ::: customers
        .drop(mainRequests.size)
        .filter(
          r => AlonsoMoraPoolingAlgForRideHail.checkDistance(r.dropoff, mainRequests.map(_.dropoff), searchRadius)
        )
    }

    val potentialTrips = mutable.ListBuffer.empty[AssignmentKey]
    // consider solo rides as initial potential trips
    customers
      .take(solutionSpaceSizePerVehicle)
      .flatten(
        c =>
          getRidehailSchedule(v.schedule, List(c.pickup, c.dropoff), v.vehicleRemainingRangeInMeters.toInt, skimmer)
            .map(schedule => (c, schedule))
      )
      .foreach {
        case (c, schedule) =>
          val trip = RideHailTrip(List(c), schedule)
          potentialTrips.append((trip, v, computeCost(trip, v)))
      }

    // if no solo ride is possible, returns
    if (potentialTrips.isEmpty)
      return List.empty[AssignmentKey]

    // building pooled rides from bottom up
    val numPassengers = v.getFreeSeats
    for (k <- 2 to numPassengers) {
      val potentialTripsWithKPassengers = mutable.ListBuffer.empty[AssignmentKey]
      potentialTrips.zipWithIndex.foreach {
        case ((t1, _, _), pt1_index) =>
          potentialTrips
            .drop(pt1_index)
            .filter {
              case (t2, _, _) =>
                !(t2.requests exists (s => t1.requests contains s)) && (t1.requests.size + t2.requests.size) == k
            }
            .foreach {
              case (t2, _, _) =>
                getRidehailSchedule(
                  v.schedule,
                  (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
                  v.vehicleRemainingRangeInMeters.toInt,
                  skimmer
                ) match {
                  case Some(schedule) =>
                    val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
                    val cost = computeCost(t, v)
                    if (potentialTripsWithKPassengers.size == 2) {
                      // then replace the trip with highest sum of delays
                      val ((_, _, tripWithLargestDelayCost), index) =
                        potentialTripsWithKPassengers.filter(_._1.requests.size == k).zipWithIndex.maxBy(_._1._3)
                      if (tripWithLargestDelayCost > cost) {
                        potentialTripsWithKPassengers.patch(index, Seq((t, v, cost)), 1)
                      }
                    } else {
                      // then add the new trip
                      potentialTripsWithKPassengers.append((t, v, cost))
                    }
                  case _ =>
                }
            }
      }
      potentialTrips.appendAll(potentialTripsWithKPassengers)
    }
    potentialTrips.toList
  }

  private def greedyAssignment(trips: List[AssignmentKey]): List[AssignmentKey] = {
    val Rok = mutable.HashSet.empty[CustomerRequest]
    val Vok = mutable.HashSet.empty[VehicleAndSchedule]
    val greedyAssignmentList = mutable.ListBuffer.empty[AssignmentKey]
    var tripsSorted = trips.sortBy(-_._1.requests.size)
    while (tripsSorted.nonEmpty) {
      tripsSorted.filter(_._1.requests.size == tripsSorted.head._1.requests.size).sortBy(_._3).foreach {
        case (trip, vehicle, cost) if (!Vok.contains(vehicle) && !trip.requests.exists(r => Rok.contains(r))) =>
          trip.requests.foreach(Rok.add)
          Vok.add(vehicle)
          greedyAssignmentList.append((trip, vehicle, cost))
        case _ =>
      }
      tripsSorted = tripsSorted.filter(t => !Vok.contains(t._2) && !t._1.requests.exists(r => Rok.contains(r)))
    }
    greedyAssignmentList.toList
  }

  private def computeCost(trip: RideHailTrip, vehicle: VehicleAndSchedule): Double = {
    trip.requests.size * trip.sumOfDelaysAsFraction + (vehicle.getFreeSeats - trip.requests.size) * 1.0
  }

}
