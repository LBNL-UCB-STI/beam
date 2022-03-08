package beam.agentsim.agents.ridehail

import java.math.BigInteger

import beam.agentsim.agents.ridehail.RideHailMatching.{CustomerRequest, RideHailTrip, VehicleAndSchedule}
import beam.sim.BeamServices
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AsyncGreedyVehicleCentricMatching(
  demand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  services: BeamServices
) extends RideHailMatching(services) {

  private implicit val beamServices: BeamServices = services

  override def matchAndAssign(tick: Int): Future[List[RideHailTrip]] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { vehicleCentricMatching(v) }
      })
      .map(result => greedyAssignment(result.flatten))
      .recover { case e =>
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
    var customers = RideHailMatching.getRequestsWithinGeofence(
      v,
      demand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
    )

    // heading same direction
    customers = RideHailMatching.getNearbyRequestsHeadingSameDirection(v, customers, solutionSpaceSizePerVehicle)

    // solution size resizing
    customers = customers.take(solutionSpaceSizePerVehicle)

    val potentialTrips = mutable.ListBuffer.empty[(RideHailTrip, Double)]
    // consider solo rides as initial potential trips
    customers
      .flatten(c => RideHailMatching.getRideHailTrip(v, List(c), services))
      .foreach(t => potentialTrips.append((t, computeCost(t))))

    // if no solo ride is possible, returns
    if (potentialTrips.isEmpty)
      return List.empty[(RideHailTrip, Double)]

    // building pooled rides from bottom up
    val numFreeSeats = v.getFreeSeats
    for (k <- 2 to numFreeSeats) {
      val tripsWithKPassengers = mutable.ListBuffer.empty[(RideHailTrip, Double)]
      val numCombinations = nCr(solutionSpaceSizePerVehicle, k).doubleValue()
      val solutionSizePerPool = if (numCombinations <= 0) {
        Int.MaxValue
      } else {
        solutionSpaceSizePerVehicle * Math.sqrt(numCombinations)
      }
      val combinations = ListBuffer.empty[String]
      for ((t1, _) <- potentialTrips) {
        for (
          (t2, _) <- potentialTrips.filter(t2p =>
            !t2p._1.requests.exists(t1.requests.contains) && (t1.requests.size + t2p._1.requests.size) == k
          )
        ) {
          val temp = t1.requests ++ t2.requests
          val matchId = temp.sortBy(_.getId).map(_.getId).mkString(",")
          if (!combinations.contains(matchId)) {
            RideHailMatching.getRideHailTrip(v, temp, services).foreach { t =>
              combinations.append(t.matchId)
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
            }
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

  def nCr(n: Int, r: Int): BigInteger = fact(n).divide(fact(r).multiply(fact(n - r)))

  def fact(n: Int): BigInteger = {
    var res: BigInteger = BigInteger.valueOf(1)
    for (i <- 2 to n) {
      res = res.multiply(BigInteger.valueOf(i))
    }
    res
  }
}
