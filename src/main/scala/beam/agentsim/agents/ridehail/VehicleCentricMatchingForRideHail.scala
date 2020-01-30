package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RHMatchingToolkit.{
  CustomerRequest,
  RHMatchingAlgorithm,
  RideHailTrip,
  VehicleAndSchedule
}
import beam.router.Modes.BeamMode
import beam.router.skim.SkimsUtils
import beam.sim.BeamServices
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class VehicleCentricMatchingForRideHail(
  demand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  services: BeamServices
) extends RHMatchingAlgorithm {

  private val solutionSpaceSizePerVehicle =
    services.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.numRequestsPerVehicle
  private val waitingTimeInSec =
    services.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec
  private val searchRadius = waitingTimeInSec * SkimsUtils.speedMeterPerSec(BeamMode.CAV)
  private implicit val beamServices: BeamServices = services

  override def matchAndAssign(tick: Int): Future[List[RideHailTrip]] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { vehicleCentricMatching(v) }
      })
      .map(result => greedyAssignment(result.flatten))
      .recover {
        case e =>
          println(e.getMessage)
          List.empty[RideHailTrip]
      }
  }

  private def vehicleCentricMatching(
    v: VehicleAndSchedule
  ): List[(RideHailTrip, Double)] = {
    val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
    val center = requestWithCurrentVehiclePosition.activity.getCoord

    // get all customer requests located at a proximity to the vehicle
    var customers = RHMatchingToolkit.getRequestsWithinGeofence(
      v,
      demand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
    )

    // heading same direction
    customers = RHMatchingToolkit.getNearbyRequestsHeadingSameDirection(v, customers, solutionSpaceSizePerVehicle)

    // solution size resizing
    customers = customers.take(solutionSpaceSizePerVehicle)

    val potentialTrips = mutable.ListBuffer.empty[(RideHailTrip, Double)]
    // consider solo rides as initial potential trips
    customers
      .flatten(
        c =>
          RHMatchingToolkit
            .getRidehailSchedule(v.schedule, List(c.pickup, c.dropoff), v.vehicleRemainingRangeInMeters.toInt, services)
            .map(schedule => (c, schedule))
      )
      .foreach {
        case (c, schedule) =>
          val t = RideHailTrip(List(c), schedule, Some(v))
          val cost = computeCost(t)
          potentialTrips.append((t, cost))
      }

    // if no solo ride is possible, returns
    if (potentialTrips.isEmpty)
      return List.empty[(RideHailTrip, Double)]

    // building pooled rides from bottom up
    val numPassengers = v.getFreeSeats
    for (k <- 2 to numPassengers) {
      val tripsWithKPassengers = mutable.ListBuffer.empty[(RideHailTrip, Double)]
      val solutionSizePerPool = Math.pow(solutionSpaceSizePerVehicle, k-1)
      potentialTrips.zipWithIndex.foreach {
        case ((t1, _), pt1_index) =>
          potentialTrips
            .drop(pt1_index)
            .filter {
              case (t2, _) =>
                !(t2.requests exists (s => t1.requests contains s)) && (t1.requests.size + t2.requests.size) == k
            }
            .foreach {
              case (t2, _) =>
                val requests = t1.requests ++ t2.requests
                RHMatchingToolkit.getRidehailSchedule(
                  v.schedule,
                  requests.flatMap(x => List(x.pickup, x.dropoff)),
                  v.vehicleRemainingRangeInMeters.toInt,
                  services
                ) match {
                  case Some(schedule) =>
                    val t = RideHailTrip(requests, schedule, Some(v))
                    val cost = computeCost(t)
                    if (tripsWithKPassengers.size == solutionSizePerPool) {
                      // then replace the trip with highest sum of delays
                      val ((_, tripWithHighestCost), index) = tripsWithKPassengers.zipWithIndex.maxBy(_._1._2)
                      if (tripWithHighestCost > cost) {
                        tripsWithKPassengers.remove(index)
                      }
                    }
                    if (tripsWithKPassengers.size < solutionSizePerPool) {
                      tripsWithKPassengers.append((t, cost))
                    }
                  case _ =>
                }
            }
      }
      potentialTrips.appendAll(tripsWithKPassengers)
    }
    potentialTrips.toList
  }

  private def greedyAssignment(trips: List[(RideHailTrip, Double)]): List[RideHailTrip] = {
    val greedyAssignmentList = mutable.ListBuffer.empty[RideHailTrip]
    var tripsByPoolSize = trips.sortBy(_._2)
    while (tripsByPoolSize.nonEmpty) {
      val (trip, _) = tripsByPoolSize.head
      greedyAssignmentList.append(trip)
      tripsByPoolSize =
        tripsByPoolSize.filter(t => t._1.vehicle != trip.vehicle && !t._1.requests.exists(trip.requests.contains))
    }
    greedyAssignmentList.toList
  }

  private def computeCost(trip: RideHailTrip): Double = {
    val passengers = trip.requests.size + trip.vehicle.get.getNoPassengers
    val delay = trip.sumOfDelays
    val maximum_delay = trip.upperBoundDelays
    val cost = passengers + (1 - (delay / maximum_delay.toDouble))
    -1 * cost
  }
}
