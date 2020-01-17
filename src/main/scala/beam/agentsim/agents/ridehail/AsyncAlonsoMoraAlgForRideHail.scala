package beam.agentsim.agents.ridehail

import beam.agentsim.agents.EnRoute
import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.Modes.BeamMode
import beam.router.skim.{ODSkims, Skims, SkimsUtils}
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import org.jgrapht.graph.DefaultEdge
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AsyncAlonsoMoraAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices
) {

  private val solutionSpaceSizePerVehicle =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.numRequestsPerVehicle

  private val waitingTimeInSec =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.waitingTimeInSec

  private def matchVehicleRequests(v: VehicleAndSchedule): (List[RTVGraphNode], List[(RTVGraphNode, RTVGraphNode)]) = {
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    val vertices = MListBuffer.empty[RTVGraphNode]
    val edges = MListBuffer.empty[(RTVGraphNode, RTVGraphNode)]
    val finalRequestsList = MListBuffer.empty[RideHailTrip]
    val requestWithCurrentVehiclePosition = v.getRequestWithCurrentVehiclePosition
    val center = requestWithCurrentVehiclePosition.activity.getCoord
    val searchRadius = waitingTimeInSec * SkimsUtils.speedMeterPerSec(BeamMode.CAV)
    var requests = v.geofence match {
      case Some(gf) =>
        val gfCenter = new Coord(gf.geofenceX, gf.geofenceY)
        spatialDemand
          .getDisk(center.getX, center.getY, searchRadius)
          .asScala
          .filter(
            r =>
              GeoUtils.distFormula(r.pickup.activity.getCoord, gfCenter) <= gf.geofenceRadius &&
              GeoUtils.distFormula(r.dropoff.activity.getCoord, gfCenter) <= gf.geofenceRadius
          )
          .toList
      case _ =>
        spatialDemand.getDisk(center.getX, center.getY, searchRadius).asScala.toList
    }
    requests = requests.sortBy(r => GeoUtils.minkowskiDistFormula(center, r.pickup.activity.getCoord))
    if (requestWithCurrentVehiclePosition.tag == EnRoute) {
      val i = v.schedule.indexWhere(_.tag == EnRoute)
      val nextTasks = v.schedule.slice(0, i)
      requests = requests.filter(r => AlonsoMoraPoolingAlgForRideHail.checkDistance(r.dropoff, nextTasks, searchRadius))
    }
    requests
      .take(solutionSpaceSizePerVehicle) foreach (
      r =>
        AlonsoMoraPoolingAlgForRideHail.getRidehailSchedule(
          v.schedule,
          List(r.pickup, r.dropoff),
          v.vehicleRemainingRangeInMeters.toInt,
          beamServices
        ) match {
          case Some(schedule) =>
            val t = RideHailTrip(List(r), schedule)
            finalRequestsList append t
            if (!vertices.contains(v)) vertices append v
            vertices append (r, t)
            edges append ((r, t), (t, v))
          case _ =>
        }
    )
    if (finalRequestsList.nonEmpty) {
      for (k <- 2 until v.getFreeSeats + 1) {
        val kRequestsList = MListBuffer.empty[RideHailTrip]
        for {
          t1 <- finalRequestsList
          t2 <- finalRequestsList
            .drop(finalRequestsList.indexOf(t1))
            .withFilter(
              x => !(x.requests exists (s => t1.requests contains s)) && (t1.requests.size + x.requests.size) == k
            )
        } yield {
          AlonsoMoraPoolingAlgForRideHail.getRidehailSchedule(
            v.schedule,
            (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
            v.vehicleRemainingRangeInMeters.toInt,
            beamServices
          ) match {
            case Some(schedule) =>
              val t = RideHailTrip(t1.requests ++ t2.requests, schedule)
              kRequestsList append t
              vertices append t
              t.requests.foldLeft(()) { case (_, r) => edges append ((r, t)) }
              edges append ((t, v))
            case _ =>
          }
        }
        finalRequestsList.appendAll(kRequestsList)
      }
    }
    (vertices.toList, edges.toList)
  }

  private def asyncBuildOfRSVGraph(): Future[AlonsoMoraPoolingAlgForRideHail.RTVGraph] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { matchVehicleRequests(v) }
      })
      .map { result =>
        val rTvG = AlonsoMoraPoolingAlgForRideHail.RTVGraph(classOf[DefaultEdge])
        result foreach {
          case (vertices, edges) =>
            vertices foreach (vertex => rTvG.addVertex(vertex))
            edges foreach { case (vertexSrc, vertexDst) => rTvG.addEdge(vertexSrc, vertexDst) }
        }
        rTvG
      }
      .recover {
        case e =>
          println(e.getMessage)
          AlonsoMoraPoolingAlgForRideHail.RTVGraph(classOf[DefaultEdge])
      }
  }

  def matchAndAssign(tick: Int): Future[List[(RideHailTrip, VehicleAndSchedule, Double)]] = {
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    asyncBuildOfRSVGraph().map(AlonsoMoraPoolingAlgForRideHail.greedyAssignment(_, V, solutionSpaceSizePerVehicle))
  }
}
