package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.AlonsoMoraPoolingAlgForRideHail._
import beam.agentsim.agents.{Dropoff, MobilityRequestType, Pickup}
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.BeamSkimmer
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.vehiclesharing.VehicleManager
import org.jgrapht.graph.DefaultEdge
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.immutable.List
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class AsyncAlonsoMoraAlgForRideHail(
  spatialDemand: QuadTree[CustomerRequest],
  supply: List[VehicleAndSchedule],
  timeWindow: Map[MobilityRequestType, Double],
  maxRequestsPerVehicle: Int,
  beamServices: BeamServices
)(implicit val skimmer: BeamSkimmer) {

  private def vehicle2Requests(v: VehicleAndSchedule): (List[RTVGraphNode], List[(RTVGraphNode, RTVGraphNode)]) = {
    import scala.collection.mutable.{ListBuffer => MListBuffer}
    if (v.getFreeSeats < 4) {
      val i = 0
    }
    val vertices = MListBuffer.empty[RTVGraphNode]
    val edges = MListBuffer.empty[(RTVGraphNode, RTVGraphNode)]
    val finalRequestsList = MListBuffer.empty[RideHailTrip]
    val center = v.getRequestWithCurrentVehiclePosition.activity.getCoord
    //val currentTimeOfVehicle = v.getRequestWithCurrentVehiclePosition.baselineNonPooledTime
    val searchRadius = timeWindow(Pickup) * BeamSkimmer.speedMeterPerSec(BeamMode.CAV)
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
    //.filter(_.pickup.baselineNonPooledTime >= currentTimeOfVehicle)
      .sortBy(x => GeoUtils.minkowskiDistFormula(center, x.pickup.activity.getCoord))
      .take(maxRequestsPerVehicle) foreach (
      r =>
        AlonsoMoraPoolingAlgForRideHail
          .getRidehailSchedule(timeWindow, v.schedule, List(r.pickup, r.dropoff), beamServices) match {
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
          AlonsoMoraPoolingAlgForRideHail
            .getRidehailSchedule(
              timeWindow,
              v.schedule,
              (t1.requests ++ t2.requests).flatMap(x => List(x.pickup, x.dropoff)),
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

  def asyncBuildOfRTVGraph(): Future[AlonsoMoraPoolingAlgForRideHail.RTVGraph] = {
    Future
      .sequence(supply.withFilter(_.getFreeSeats >= 1).map { v =>
        Future { vehicle2Requests(v) }
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

  def greedyAssignment(tick: Int): Future[List[(RideHailTrip, VehicleAndSchedule, Double)]] = {

    skimByTazId(supply.map(_.vehicle.toStreetVehicle.locationUTM.loc), tick, "available-vehicles")
    skimByTazId(spatialDemand.values().asScala.map(_.pickup.activity.getCoord), tick, "pooling-requests")

    val rTvGFuture = asyncBuildOfRTVGraph()
    val V: Int = supply.foldLeft(0) { case (maxCapacity, v) => Math max (maxCapacity, v.getFreeSeats) }
    rTvGFuture.map { rTvG =>
      val greedyAssignmentList = mutable.ListBuffer.empty[(RideHailTrip, VehicleAndSchedule, Double)]
      val Rok = mutable.ListBuffer.empty[CustomerRequest]
      val Vok = mutable.ListBuffer.empty[VehicleAndSchedule]
      for (k <- V to 1 by -1) {
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
            val C0 = timeWindow(Pickup) + timeWindow(Dropoff) * 3600
            val cost = trip.cost + C0 * (maxRequestsPerVehicle - trip.requests.size)
            (trip, vehicle, cost)
          }
          .toList
          .sortBy(_._3)
          .foldLeft(()) {
            case (_, (trip, vehicle, cost)) =>
              if (!(Vok contains vehicle) && !(trip.requests exists (r => Rok contains r))) {
                Rok.appendAll(trip.requests)
                Vok.append(vehicle)
                greedyAssignmentList.append((trip, vehicle, cost))
              }
          }
      }
      skimAssignmentList(greedyAssignmentList, tick, 1)
      skimAssignmentList(greedyAssignmentList, tick, 2)
      skimAssignmentList(greedyAssignmentList, tick, 3)
      skimAssignmentList(greedyAssignmentList, tick, 4)
      val totTime = greedyAssignmentList.filter(a => a._2.getNoPassengers + a._1.requests.size > 1)
        .map(_._1.schedule.filter(_.serviceTime >= tick).map(_.serviceTime))
        .map(scheduleTime => scheduleTime.last - scheduleTime.head)
        .sum
      skimByValue(totTime, tick, "evtt-pooling-requests")
      greedyAssignmentList.toList
    }
  }

  def skimAssignmentList(assignmentList: mutable.ListBuffer[(RideHailTrip, VehicleAndSchedule, Double)], tick: Int, nbPassenger: Int) = {
    skimByTazId(
      assignmentList
        .filter(a => a._2.getNoPassengers + a._1.requests.size == nbPassenger)
        .flatMap(_._1.requests.map(_.pickup.activity.getCoord)),
      tick,
      s"matched-${nbPassenger}P-pooling-requests")
  }

  def skimByTazId(locations: Iterable[Coord], tick: Int, label: String) = {
    locations
      .groupBy( loc => beamServices.beamScenario.tazTreeMap.getTAZ(loc.getX, loc.getY).tazId)
      .mapValues(_.size)
      .foreach { case (tazId, occurrence) =>
        skimmer.countEvents(
          tick,
          tazId,
          Id.create("pooling-alonso-mora", classOf[VehicleManager]),
          label,
          count = occurrence)
      }
  }
  def skimByValue(value: Double, tick: Int, label: String) = {
    skimmer.addValue(
      tick,
      TAZTreeMap.emptyTAZId,
      Id.create("pooling-alonso-mora", classOf[VehicleManager]),
      label,
      value)
  }
}
