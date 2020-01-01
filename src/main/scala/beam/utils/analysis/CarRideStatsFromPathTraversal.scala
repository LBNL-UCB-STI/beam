package beam.utils.analysis

import java.io.Closeable

import beam.agentsim.events.PathTraversalEvent
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode
import beam.utils.EventReader
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.JavaConverters._
import scala.util.Try

final class CarRideStatsFromPathTraversal(pathToNetwork: String) {

  val network: Network = {
    val n = NetworkUtils.createNetwork()
    new MatsimNetworkReader(n)
      .readFile(pathToNetwork)
    n
  }
  val linkMap: Map[Id[Link], Link] = network.getLinks.asScala.toMap

  def eventsFilter(event: Event): Boolean = {
    val attribs = event.getAttributes
    // We need only PathTraversal
    val isNeededEvent = event.getEventType == "PathTraversal"
    isNeededEvent
  }

  def computeStatsConsiderParking(eventsFilePath: String): List[RideStat] = {
    val freeFlowTravelTimeCalc = new FreeFlowTravelTime
    val (ptes: Iterator[PathTraversalEvent], closable: Closeable) = {
      val (e, c) = EventReader.fromCsvFile(eventsFilePath, eventsFilter)
      (
        e.map(PathTraversalEvent.apply)
          .filter(pte => pte.mode == BeamMode.CAR && !pte.vehicleId.toString.startsWith("rideHailVehicle")),
        c
      )
    }
    try {
      val drivingWithParkingPtes = ptes.toVector
        .groupBy(x => x.vehicleId)
        .map {
          case (vehId, xs) =>
            val sorted = xs.sortBy(x => x.departureTime)
            if (sorted.length % 2 == 1) {
              println(s"$vehId has ${sorted.length} events")
            }
            sorted.sliding(2, 2).flatMap { ptes =>
              val maybeDriving = ptes.lift(0)
              val maybeParking = ptes.lift(1)
              for {
                driving <- maybeDriving
                parking <- maybeParking
              } yield (driving, parking)
            }
        }
        .flatten

      val stats = drivingWithParkingPtes.foldLeft(List.empty[RideStat]) {
        case (acc, (driving, parking)) =>
          if (driving.arrivalTime != parking.departureTime) {
            println(s"""
                       |$driving
                       |$parking""".stripMargin)
          }
          val travelTime =
            ((driving.arrivalTime - driving.departureTime) + (parking.arrivalTime - parking.departureTime)).toDouble
          val length = driving.legLength + parking.legLength
          val linkIds = (driving.linkIds ++ parking.linkIds).map(lid => linkMap(Id.createLinkId(lid)))
          val freeFlowTravelTime: Double = calcFreeFlowDuration(freeFlowTravelTimeCalc, linkIds)
          RideStat(driving.vehicleId.toString, travelTime, length, freeFlowTravelTime) :: acc
      }
      stats
    } finally {
      Try(closable.close())
    }
  }

  def calcFreeFlowDuration(freeFlowTravelTime: FreeFlowTravelTime, linkIds: IndexedSeq[Link]): Double = {
    linkIds.foldLeft(0.0) {
      case (acc, link) =>
        val t = freeFlowTravelTime.getLinkTravelTime(link, 0.0, null, null)
        acc + t
    }
  }
}
