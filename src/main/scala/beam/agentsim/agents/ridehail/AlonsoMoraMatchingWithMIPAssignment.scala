package beam.agentsim.agents.ridehail

import beam.agentsim.agents.MobilityRequest
import beam.agentsim.agents.ridehail.RideHailMatching._
import beam.sim.BeamServices
import com.github.beam.OrToolsLoader
import com.google.ortools.linearsolver.{MPSolver, MPVariable}
import org.jgrapht.graph.DefaultEdge
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable
import scala.collection.mutable.ListBuffer
import scala.concurrent.Future

object AlonsoMoraMatchingWithMIPAssignment {

  private lazy val initialize: Unit = {
    OrToolsLoader.load()
  }
}

class AlonsoMoraMatchingWithMIPAssignment(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices
) extends RideHailMatching(beamServices) {

  AlonsoMoraMatchingWithMIPAssignment.initialize

  private implicit val services: BeamServices = beamServices

  // a greedy assignment using a cost function
  override def matchAndAssign(tick: Int): Future[List[RideHailTrip]] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Future {
      val rvG = pairwiseRVGraph
      val rTvG = rTVGraph(rvG)
      val assignment = optimalAssignment(rTvG)
      assignment
    }
  }

  // Request Vehicle Graph
  def pairwiseRVGraph: RVGraph = {
    val rvG = RVGraph(classOf[RideHailTrip])
    for (r1: CustomerRequest <- spatialDemand.values().asScala) {
      val center = r1.pickup.activity.getCoord
      spatialDemand.getDisk(center.getX, center.getY, searchRadius).asScala.foreach {
        case r2 if r1 != r2 && !rvG.containsEdge(r1, r2) =>
          val startPoint =
            if (r1.pickup.baselineNonPooledTime <= r2.pickup.baselineNonPooledTime) r1.pickup else r2.pickup
          RideHailMatching
            .getRideHailSchedule(
              List.empty[MobilityRequest],
              List(r1.pickup, r1.dropoff, r2.pickup, r2.dropoff),
              Integer.MAX_VALUE,
              startPoint,
              beamServices,
              None
            )
            .foreach { schedule =>
              rvG.addVertex(r2)
              rvG.addVertex(r1)
              rvG.addEdge(r1, r2, RideHailTrip(List(r1, r2), schedule, None))
            }
        case _ => // nothing
      }
    }

    for (v: VehicleAndSchedule <- supply.withFilter(_.getFreeSeats >= 1)) {
      val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
      val center = requestWithCurrentVehiclePosition.activity.getCoord

      // get all customer requests located at a proximity to the vehicle
      var customers = RideHailMatching.getRequestsWithinGeofence(
        v,
        spatialDemand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
      )
      // heading same direction
      customers = RideHailMatching.getNearbyRequestsHeadingSameDirection(v, customers, solutionSpaceSizePerVehicle)

      // solution size resizing
      customers = customers.take(solutionSpaceSizePerVehicle)

      customers
        .foreach(r =>
          RideHailMatching
            .getRideHailSchedule(
              v.schedule,
              List(r.pickup, r.dropoff),
              v.vehicleRemainingRangeInMeters.toInt,
              v.getRequestWithCurrentVehiclePosition,
              beamServices,
              Some(v.vehicle.beamVehicleType)
            )
            .foreach { schedule =>
              rvG.addVertex(v)
              rvG.addVertex(r)
              rvG.addEdge(v, r, RideHailTrip(List(r), schedule, Some(v)))
            }
        )
    }
    rvG
  }

  // Request Trip Vehicle Graph
  def rTVGraph(rvG: RVGraph): RTVGraph = {
    val rTvG = RTVGraph(classOf[DefaultEdge])

    for (v <- supply.filter(rvG.containsVertex)) {
      rTvG.addVertex(v)
      val finalRequestsList: ListBuffer[RideHailTrip] = ListBuffer.empty[RideHailTrip]
      val individualRequestsList = ListBuffer.empty[RideHailTrip]
      for (t <- rvG.outgoingEdgesOf(v).asScala) {
        individualRequestsList.append(t)
        rTvG.addVertex(t)
        rTvG.addVertex(t.requests.head)
        rTvG.addEdge(t.requests.head, t)
        rTvG.addEdge(t, v)
      }
      finalRequestsList.appendAll(individualRequestsList)

      if (v.getFreeSeats > 1) {
        val pairRequestsList = ListBuffer.empty[RideHailTrip]
        val combinations = ListBuffer.empty[String]
        for (t1 <- individualRequestsList) {
          for (
            t2 <- individualRequestsList
              .filter(x => t1 != x && rvG.containsEdge(t1.requests.head, x.requests.head))
          ) {
            val temp = t1.requests ++ t2.requests
            val matchId = temp.sortBy(_.getId).map(_.getId).mkString(",")
            if (!combinations.contains(matchId)) {
              RideHailMatching.getRideHailTrip(v, temp, beamServices).foreach { t =>
                combinations.append(t.matchId)
                pairRequestsList append t
                rTvG.addVertex(t)
                rTvG.addEdge(t1.requests.head, t)
                rTvG.addEdge(t2.requests.head, t)
                rTvG.addEdge(t, v)
              }
            }
          }
        }
        finalRequestsList.appendAll(pairRequestsList)

        for (k <- 3 to v.getFreeSeats) {
          val kRequestsList = ListBuffer.empty[RideHailTrip]
          for (t1 <- finalRequestsList) {
            for (
              t2 <- finalRequestsList
                .drop(finalRequestsList.indexOf(t1))
                .filter(x =>
                  !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
                )
            ) {
              val temp = t1.requests ++ t2.requests
              val matchId = temp.sortBy(_.getId).map(_.getId).mkString(",")
              if (!combinations.contains(matchId)) {
                RideHailMatching.getRideHailTrip(v, temp, beamServices).foreach { t =>
                  combinations.append(t.matchId)
                  kRequestsList.append(t)
                  rTvG.addVertex(t)
                  t.requests.foreach(rTvG.addEdge(_, t))
                  rTvG.addEdge(t, v)
                }
              }
            }
          }
          finalRequestsList.appendAll(kRequestsList)
        }
      }
    }
    rTvG
  }

  def optimalAssignment(rTvG: RTVGraph): List[RideHailTrip] = {
    val optimalAssignment = mutable.ListBuffer.empty[RideHailTrip]
    val combinations = rTvG
      .vertexSet()
      .asScala
      .filter(t => t.isInstanceOf[RideHailTrip])
      .map(_.asInstanceOf[RideHailTrip])
      .toList
    if (combinations.nonEmpty) {
      val trips = combinations.map(_.matchId).distinct.toArray
      val vehicles = supply.toArray
      import scala.language.implicitConversions
      val solver: MPSolver =
        new MPSolver("SolveAssignmentProblemMIP", MPSolver.OptimizationProblemType.CBC_MIXED_INTEGER_PROGRAMMING)
      //solver.setNumThreads(96)
      val objective = solver.objective()
      val epsilonCostMap = mutable.Map.empty[Integer, mutable.Map[Integer, (MPVariable, Double)]]
      combinations.groupBy(_.vehicle).foreach { case (vehicle, alternatives) =>
        val j = vehicles.indexOf(vehicle.get)
        // + constraint 1
        val ct1_j = solver.makeConstraint(0.0, 1.0, s"ct1_$j")
        alternatives.foreach { trip =>
          val i = trips.indexOf(trip.matchId)
          val c_ij = trip.sumOfDelays
          val (epsilon_ij, _) = epsilonCostMap
            .getOrElseUpdate(i, mutable.Map.empty[Integer, (MPVariable, Double)])
            .getOrElseUpdate(j, (solver.makeBoolVar(s"epsilon($i,$j)"), c_ij))
          ct1_j.setCoefficient(epsilon_ij, 1)
        }
      }
      spatialDemand
        .values()
        .asScala
        .map(r => combinations.filter(_.matchId.contains(r.getId)))
        .filter(_.nonEmpty)
        .zipWithIndex
        .foreach { case (alternatives, k) =>
          val c_k0 = 24 * 3600
          val chiVar = solver.makeBoolVar(s"chi($k)")
          val ct2_k = solver.makeConstraint(1.0, 1.0, s"ct2_$k")
          alternatives.foreach { trip =>
            val i = trips.indexOf(trip.matchId)
            val j = vehicles.indexOf(trip.vehicle.get)
            ct2_k.setCoefficient(epsilonCostMap(i)(j)._1, 1)
          }
          ct2_k.setCoefficient(chiVar, 1)
          // Ck0 * Chi
          objective.setCoefficient(chiVar, c_k0)
        }
      // setting up the first half of the objective function
      epsilonCostMap.flatMap(_._2.values).foreach { case (epsilon, c) =>
        objective.setCoefficient(epsilon, c)
      }

      objective.setMinimization()
      val resultStatus = solver.solve
      if (resultStatus ne MPSolver.ResultStatus.OPTIMAL) {
        logger.error("The problem does not have an optimal solution!")
      } else {
        logger.info("optimal solution had been found")
        for (i <- epsilonCostMap.keys) {
          for (j <- epsilonCostMap(i).keys) {
            val vehicle = vehicles(j)
            val trip = combinations.find(c => c.vehicle.get == vehicle && c.matchId == trips(i)).get
            if (epsilonCostMap(i)(j)._1.solutionValue() == 1) {
              optimalAssignment.append(trip)
            }
          }
        }
      }
      solver.delete()
    }
    optimalAssignment.toList
  }
}
