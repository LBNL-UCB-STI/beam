package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
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
  private implicit val implicitServices = services

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
    var customers = MatchmakingUtils.getRequestsWithinGeofence(v, demand.getDisk(center.getX, center.getY, searchRadius).asScala.toList)

    // heading same direction
    customers = MatchmakingUtils.getNearbyRequestsHeadingSameDirection(v, customers)

    // solution size resizing
    customers = customers
      .sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
      .take(solutionSpaceSizePerVehicle)

    val potentialTrips = mutable.ListBuffer.empty[AssignmentKey]
    // consider solo rides as initial potential trips
    customers
      .flatten(
        c =>
          MatchmakingUtils.getRidehailSchedule(v.schedule, List(c.pickup, c.dropoff), v.vehicleRemainingRangeInMeters.toInt, skimmer)
            .map(schedule => (c, schedule))
      )
      .foreach {
        case (c, schedule) =>
          val trip = RideHailTrip(List(c), schedule)
          potentialTrips.append((trip, v, MatchmakingUtils.computeGreedyCost(trip, v)))
      }

    // if no solo ride is possible, returns
    if (potentialTrips.isEmpty)
      return List.empty[AssignmentKey]

    // building pooled rides from bottom up
    val numPassengers = v.getFreeSeats
    for (k <- 2 to numPassengers) {
      val tripsWithKPassengers = mutable.ListBuffer.empty[AssignmentKey]
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
                val requests = t1.requests ++ t2.requests
                MatchmakingUtils.getRidehailSchedule(
                  v.schedule,
                  requests.flatMap(x => List(x.pickup, x.dropoff)),
                  v.vehicleRemainingRangeInMeters.toInt,
                  skimmer
                ) match {
                  case Some(schedule) =>
                    val t = RideHailTrip(requests, schedule)
                    val cost = MatchmakingUtils.computeGreedyCost(t, v)
                    tripsWithKPassengers.append((t, v, cost))
                  case _ =>
                }
            }
      }
      potentialTrips.appendAll(tripsWithKPassengers)
    }
    potentialTrips.toList
  }

  private def greedyAssignment(trips: List[AssignmentKey]): List[AssignmentKey] = {
    val greedyAssignmentList = mutable.ListBuffer.empty[AssignmentKey]
    var tripsByPoolSize = trips.sortBy(_._3)
    while (tripsByPoolSize.nonEmpty) {
      val (trip, vehicle, cost) = tripsByPoolSize.head
      greedyAssignmentList.append((trip, vehicle, cost))
      tripsByPoolSize = tripsByPoolSize.filter(t => t._2 != vehicle && !t._1.requests.exists(trip.requests.contains))
    }
    greedyAssignmentList.toList
  }
}
