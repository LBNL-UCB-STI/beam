package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import org.jgrapht.graph.DefaultEdge
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.collection.mutable
import scala.collection.immutable

class AsyncAlonsoMoraAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  beamServices: BeamServices,
  skimmer: BeamSkimmer
) {

  var alternativeOptionsScore = immutable.HashMap.empty[Id[Person], Double]

  private def matchVehicleRequests(v: VehicleAndSchedule): (List[RTVGraphNode], List[(RTVGraphNode, RTVGraphNode)]) = {
    val vertices = mutable.ListBuffer.empty[RTVGraphNode]
    val edges = mutable.ListBuffer.empty[(RTVGraphNode, RTVGraphNode)]
    val finalRequestsList = mutable.ListBuffer.empty[RideHailTrip]
    val center = v.getRequestWithCurrentVehiclePosition.activity.getCoord
    val searchRadius = beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.maxWaitTimeInSec * BeamSkimmer
      .speedMeterPerSec(BeamMode.CAV)
    val solutionSpaceSizePerVehicle = Math
      .round(
        v.getFreeSeats * beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.ratioOfSolutionSpaceToAvailability
      )
      .toInt
    val requests = v.geofence match {
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
    requests
      .filter(
        x =>
          !alternativeOptionsScore
            .filter(
              _._2 > beamServices.beamConfig.beam.agentsim.agents.rideHail.allocationManager.alonsoMora.ratioOfSolutionSpaceToRequest
            )
            .exists(_._1 == x.person.personId)
      )
      .sortBy(x => GeoUtils.minkowskiDistFormula(center, x.pickup.activity.getCoord))
      .take(solutionSpaceSizePerVehicle) foreach (
      r =>
        AlonsoMoraPoolingAlgForRideHail.getRidehailSchedule(
          v.schedule,
          List(r.pickup, r.dropoff),
          v.vehicleRemainingRangeInMeters.toInt,
          skimmer
        ) match {
          case Some(schedule) =>
            val t = RideHailTrip(List(r), schedule, Some(v))
            finalRequestsList append t
            if (!vertices.contains(v)) vertices append v
            vertices append (r, t)
            edges append ((r, t), (t, v))
          case _ =>
        }
    )
    if (finalRequestsList.nonEmpty) {
      for (k <- 2 until v.getFreeSeats + 1) {
        val kRequestsList = mutable.ListBuffer.empty[RideHailTrip]
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
            skimmer
          ) match {
            case Some(schedule) =>
              val t = RideHailTrip(t1.requests ++ t2.requests, schedule, Some(v))
              kRequestsList append t
              vertices append t
              t.requests.foldLeft(()) { case (_, r) => edges append ((r, t)) }
              edges append ((t, v))
            case _ =>
          }
        }
        finalRequestsList.appendAll(kRequestsList)
      }
      var personChecked = mutable.IndexedSeq.empty[Id[Person]]
      finalRequestsList.sortBy(-_.schedule.size).foreach { alternative =>
        val score = alternative.requests.size / alternative.vehicle.get.getFreeSeats
        alternative.requests.foreach { request =>
          if (!personChecked.contains(request.person.personId)) {
            val prevScore = alternativeOptionsScore.getOrElse(request.person.personId, 0.0)
            alternativeOptionsScore = alternativeOptionsScore + (request.person.personId -> (prevScore + score))
            personChecked = personChecked :+ request.person.personId
          }
        }
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
    asyncBuildOfRSVGraph().map(AlonsoMoraPoolingAlgForRideHail.greedyAssignment(_, V, beamServices.beamConfig))
  }
}
