package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RideHailMatching._
import beam.router.Modes.BeamMode
import beam.router.skim.SkimsUtils
import beam.sim.BeamServices
import org.jgrapht.graph.DefaultEdge
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable.ListBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AlonsoMoraMatchingWithAsyncGreedyAssignment(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices
) extends RideHailMatching(beamServices) {

  private implicit val implicitServices: BeamServices = beamServices

  override def matchAndAssign(tick: Int): Future[List[RideHailTrip]] = {
    asyncBuildOfRSVGraph().map {
      case rTvG if rTvG.edgeSet().isEmpty => List.empty[RideHailTrip]
      case rTvG                           => greedyAssignment(rTvG)
    }
  }

  private def matchVehicleRequests(v: VehicleAndSchedule): (List[RTVGraphNode], List[(RTVGraphNode, RTVGraphNode)]) = {
    val vertices = ListBuffer.empty[RTVGraphNode]
    val edges = ListBuffer.empty[(RTVGraphNode, RTVGraphNode)]
    val finalRequestsList = ListBuffer.empty[RideHailTrip]
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

    customers.foreach(r =>
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
          val t = RideHailTrip(List(r), schedule, Some(v))
          finalRequestsList append t
          if (!vertices.contains(v)) vertices append v
          vertices append (r, t)
          edges append ((r, t), (t, v))
        }
    )
    if (finalRequestsList.nonEmpty) {
      for (k <- 2 to v.getFreeSeats) {
        val combinations = ListBuffer.empty[String]
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
                kRequestsList append t
                vertices append t
                t.requests.foreach(r => edges.append((r, t)))
                edges append ((t, v))
              }
            }
          }
        }
        finalRequestsList.appendAll(kRequestsList)
      }
    }
    (vertices.toList, edges.toList)
  }

  private def asyncBuildOfRSVGraph(): Future[RTVGraph] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { matchVehicleRequests(v) }
      })
      .map { result =>
        val rTvG = RTVGraph(classOf[DefaultEdge])
        result foreach { case (vertices, edges) =>
          vertices foreach (vertex => rTvG.addVertex(vertex))
          edges foreach { case (vertexSrc, vertexDst) => rTvG.addEdge(vertexSrc, vertexDst) }
        }
        rTvG
      }
      .recover { case e =>
        logger.error(e.getMessage)
        RTVGraph(classOf[DefaultEdge])
      }
  }

  private def greedyAssignment(rTvG: RTVGraph): List[RideHailTrip] = {
    val greedyAssignmentList = ListBuffer.empty[RideHailTrip]
    val v_ok = ListBuffer.empty[VehicleAndSchedule]
    val r_ok = ListBuffer.empty[CustomerRequest]
    var outputList = rTvG
      .vertexSet()
      .asScala
      .filter(t => t.isInstanceOf[RideHailTrip])
      .toList
    val maxK: Int =
      outputList.maxBy(_.asInstanceOf[RideHailTrip].requests.size).asInstanceOf[RideHailTrip].requests.size
    (maxK until 0 by -1).foreach { k =>
      var temp = outputList
        .filter(t => t.asInstanceOf[RideHailTrip].requests.size == k)
        .sortBy(_.asInstanceOf[RideHailTrip].sumOfDelays)
      while (temp.nonEmpty) {
        val trip = temp.head.asInstanceOf[RideHailTrip]
        greedyAssignmentList.append(trip)
        r_ok.appendAll(trip.requests)
        v_ok.append(trip.vehicle.get)
        temp = temp.filter(t =>
          !v_ok.contains(t.asInstanceOf[RideHailTrip].vehicle.get) && !t
            .asInstanceOf[RideHailTrip]
            .requests
            .exists(r => r_ok.contains(r))
        )
      }
      outputList = outputList.filter(t =>
        !v_ok.contains(t.asInstanceOf[RideHailTrip].vehicle.get) && !t
          .asInstanceOf[RideHailTrip]
          .requests
          .exists(r => r_ok.contains(r))
      )
    }
    greedyAssignmentList.toList
  }
}
