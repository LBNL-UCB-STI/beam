package beam.utils.analysis

import java.io.Closeable
import java.nio.file.Paths

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{ModeChoiceEvent, PathTraversalEvent}
import beam.analysis.cartraveltime.{CarRideStatsFromPathTraversalEventHandler, SingleRideStat}
import beam.router.Modes.{toR5StreetMode, BeamMode}
import beam.router.model.RoutingModel
import beam.router.{FreeFlowTravelTime, LinkTravelTimeContainer}
import beam.utils.BeamVehicleUtils.readBeamVehicleTypeFile
import beam.utils.csv.CsvWriter
import beam.utils.{EventReader, ProfilingUtils, Statistics}
import com.conveyal.r5.kryo.KryoNetworkSerializer
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.transit.TransportNetwork
import org.apache.commons.io.FilenameUtils
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader
import org.matsim.core.router.util.TravelTime

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

case class TravelTimeHolder(original: Double, freeflow: Double, r5FreeFlow: Double, r5LinkStat: Option[Double])

object AverageCarTravelTimeAnalysis {

  def eventsFilter(event: Event): Boolean = {
    val attribs = event.getAttributes
    // We need only PathTraversal
    val isNeededEvent = event.getEventType == "PathTraversal"
    isNeededEvent
  }

  val vehileTypeFile =
    "D:/Work/beam/MultipleJDEQSim/baseline_art_smart-baseline-network-relax-baseline-without-noise-injection/vehicletypes-baseline.csv"
  val vehicleTypeIdToVehicleType: Map[Id[BeamVehicleType], BeamVehicleType] = readBeamVehicleTypeFile(vehileTypeFile)

  val transportNetwork: TransportNetwork =
    KryoNetworkSerializer.read(Paths.get("c:/temp/production/sfbay/r5-simple-no-local", "network.dat").toFile)

  def showStats(eventsFile: String, nPtes: Int, travelTimes: List[TravelTimeHolder]): Unit = {
    val originalStats = Statistics(travelTimes.map(_.original))
    val freeflowStats = Statistics(travelTimes.map(_.freeflow))
    val r5FreeFlowStats = Statistics(travelTimes.map(_.r5FreeFlow))
    val r5LinkStats = Statistics(travelTimes.flatMap(_.r5LinkStat))
    val msg = s"""File: $eventsFile
       |nPTEs: $nPtes
       |originalStats: $originalStats
       |freeflowStats: $freeflowStats
       |r5FreeFlowStats: $r5FreeFlowStats
       |r5LinkStats: $r5LinkStats""".stripMargin
    println(msg)
  }

  def showStats(eventsFile: String, rideStats: Seq[SingleRideStat]): Unit = {
    val travelTimeStats = Statistics(rideStats.map(_.travelTime))
    val freeFlowTravelTimeStats = Statistics(rideStats.map(_.freeFlowTravelTime))
    val length = Statistics(rideStats.map(_.distance))
    val speedStats = Statistics(rideStats.map(_.speed))
    val freeFlowSpeedStats = Statistics(rideStats.map(_.freeFlowSpeed))
    val msg = s"""File: $eventsFile
                 |number of data points: ${rideStats.size}
                 |travelTimeStats: $travelTimeStats
                 |freeFlowTravelTimeStats: $freeFlowTravelTimeStats
                 |length: $length
                 |speedStats: $speedStats
                 |freeFlowSpeedStats: $freeFlowSpeedStats""".stripMargin
    println(msg)
  }

  def main(args: Array[String]): Unit = {
    val pathToNetwork = "D:/Work/beam/MultipleJDEQSim/baseline_more_events/outputNetwork.xml.gz"
    val eventsFile0 = "C:/temp/15.events.csvh.gz"

    val carRideStatsFromPathTraversal = CarRideStatsFromPathTraversalEventHandler(pathToNetwork, eventsFile0)

    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile(pathToNetwork)
    val eventsFile40 = "C:/temp/15.events.csvh.gz"

    val statsF0 = Future {
      val rideStats = ProfilingUtils.timed(s"$eventsFile0: computeStatsConsiderParking", x => println(x)) {
        carRideStatsFromPathTraversal.calcRideStats(15)
      }
      showStats(eventsFile0, rideStats)
    }

    val fList = Future.sequence(List(statsF0))
    Await.result(fList, 1500.seconds)
  }

  def getBeamCarTravelTime(eventsFilePath: String): Unit = {
    def eventsFilter(event: Event): Boolean = {
      val attribs = event.getAttributes
      // We need only PathTraversal
      val isNeededEvent = event.getEventType == PersonArrivalEvent.EVENT_TYPE || event.getEventType == PersonDepartureEvent.EVENT_TYPE
      isNeededEvent
    }

    def mapEvent(event: Event): Event = {
      val attribs = event.getAttributes
      val personId = Id.createPersonId(attribs.get(PersonArrivalEvent.ATTRIBUTE_PERSON))
      val maybeLinkId = Option(attribs.get(PersonArrivalEvent.ATTRIBUTE_LINK)).map { linkId =>
        Id.createLinkId(linkId)
      }
      val legMode = attribs.get(PersonArrivalEvent.ATTRIBUTE_LEGMODE)
      if (event.getEventType == PersonArrivalEvent.EVENT_TYPE) {
        new PersonArrivalEvent(event.getTime, personId, maybeLinkId.orNull, legMode)
      } else {
        new PersonDepartureEvent(event.getTime, personId, maybeLinkId.orNull, legMode)
      }
    }

    val (events: Iterator[Event], closable: Closeable) = {
      val (e, c) = EventReader.fromCsvFile(eventsFilePath, eventsFilter)
      (e.map(mapEvent), c)
    }
    val carTravelTimeHandler = new TravelTimeHandler(Map.empty)
    events.foreach(carTravelTimeHandler.handleEvent)
    val x = carTravelTimeHandler.compute
      .map {
        case (mode, stat) =>
          s"$mode: $stat"
      }
      .mkString("\n")
    println(s"BeamCarTravelTime. File: $eventsFilePath\n$x")
  }

  def computeSpeed(eventsFilePath: String): Unit = {
    def eventsFilter(event: Event): Boolean = {
      val attribs = event.getAttributes
      val isNeededEvent = event.getEventType == PersonArrivalEvent.EVENT_TYPE || event.getEventType == PersonDepartureEvent.EVENT_TYPE || event.getEventType == ModeChoiceEvent.EVENT_TYPE
      isNeededEvent
    }

    def mapEvent(event: Event): Event = {
      val attribs = event.getAttributes
      event.getEventType match {
        case PersonArrivalEvent.EVENT_TYPE =>
          val personId = Id.createPersonId(attribs.get(PersonArrivalEvent.ATTRIBUTE_PERSON))
          val maybeLinkId = Option(attribs.get(PersonArrivalEvent.ATTRIBUTE_LINK)).map { linkId =>
            Id.createLinkId(linkId)
          }
          val legMode = attribs.get(PersonArrivalEvent.ATTRIBUTE_LEGMODE)
          new PersonArrivalEvent(event.getTime, personId, maybeLinkId.orNull, legMode)
        case PersonDepartureEvent.EVENT_TYPE =>
          val personId = Id.createPersonId(attribs.get(PersonDepartureEvent.ATTRIBUTE_PERSON))
          val maybeLinkId = Option(attribs.get(PersonDepartureEvent.ATTRIBUTE_LINK)).map { linkId =>
            Id.createLinkId(linkId)
          }
          val legMode = attribs.get(PersonDepartureEvent.ATTRIBUTE_LEGMODE)
          new PersonDepartureEvent(event.getTime, personId, maybeLinkId.orNull, legMode)
        case ModeChoiceEvent.EVENT_TYPE =>
          ModeChoiceEvent.apply(event)
      }
    }
    val eventPriority: Map[String, Int] = Map[String, Int](
      ModeChoiceEvent.EVENT_TYPE      -> 1,
      PersonDepartureEvent.EVENT_TYPE -> 2,
      PersonArrivalEvent.EVENT_TYPE   -> 3
    )

    val (personToEvents, closable: Closeable) = {
      val (e, c) = EventReader.fromCsvFile(eventsFilePath, eventsFilter)
      val unsortedEvents = e.map(mapEvent).toVector
      val sortedEvents = unsortedEvents
        .groupBy(x => x.getAttributes.get("person"))
        .map {
          case (personId, xs) =>
            val sortedPersonEvents = xs.sortBy { x =>
              val z = (x.getTime, eventPriority(x.getEventType))
              z
            }
            personId -> sortedPersonEvents
        }
      (sortedEvents, c)
    }
    try {

      val velocities = ArrayBuffer[Double]()

      implicit object ModeChoiceEventOrdering extends Ordering[ModeChoiceEvent] {
        override def compare(x: ModeChoiceEvent, y: ModeChoiceEvent): Int =
          java.lang.Double.compare(x.getTime, y.getTime)
      }
//      personToEvents.foreach { case (personId, events) =>
//        val queue = new mutable.PriorityQueue[ModeChoiceEvent]()
//        events.foreach {
//          case mc: ModeChoiceEvent =>
//            queue += mc
//          case arrival: PersonArrivalEvent =>
//            val personId = arrival.getPersonId
//            personToModeChoices.get(personId) match {
//              case Some(queue) =>
//                val oldestMc = queue.dequeue()
//                val len = oldestMc.length
//                depMap.remove(personId) match {
//                  case Some(departureTime) =>
//                    val time = arrival.getTime - departureTime
//                    if (time != 0) {
//                      val velocity = len / time
//                      velocities += velocity
//                    }
//                  case None =>
//                    //                  val thatPersonEvents = events.filter(x => x.getAttributes.get("person") == personId.toString)
//                    //                  println(s"Could not find person[$personId] in depMap. thatPersonEvents: $thatPersonEvents")
//                    println(s"Could not find person[$personId] in depMap.")
//                }
//              case None =>
//                // val thatPersonEvents = events.filter(x => x.getAttributes.get("person") == personId.toString)
//                // println(s"Could not find person[$personId] in personToModeChoices map. thatPersonEvents: $thatPersonEvents")
//                println(s"Could not find person[$personId] in personToModeChoices map. arrival: $arrival")
//            }
//          case dep: PersonDepartureEvent =>
//            depMap.put(dep.getPersonId, dep.getTime)
//        }
//      }
      val top100 = velocities.sortBy(x => -x).take(100).toVector
      println(s"top100: ${top100}")
      println(s"velocities: ${Statistics(velocities)}")
    } finally {
      closable.close()
    }
  }

  private def computeStats(
    network: Network,
    eventsFilePath: String,
    maybeLinkStatsFile: Option[String]
  ): (Int, List[TravelTimeHolder]) = {
    val allowedDiffPct = 10.0
    val freeFlowTravelTime = new FreeFlowTravelTime
    val linkMap: Map[Id[Link], Link] = network.getLinks.asScala.toMap
    val maybeLinkTravelTimeContainer: Option[LinkTravelTimeContainer] =
      maybeLinkStatsFile.map(new LinkTravelTimeContainer(_, 3600, 30))
    val (ptes: Iterator[PathTraversalEvent], closable: Closeable) = {
      val (e, c) = EventReader.fromCsvFile(eventsFilePath, eventsFilter)
      (e.map(PathTraversalEvent.apply).filter(pte => pte.mode.matsimMode == "car"), c)
    }
    var nPTEs: Int = 0
    val speeds = ArrayBuffer[Double]()
    val convertedDataFilePath = FilenameUtils.getBaseName(eventsFilePath) + "_more_data.csv"
    val csvWriter = new CsvWriter(convertedDataFilePath, Vector("row", "length", "duration", "speed"))

    val travelTimes = ptes.foldLeft(List.empty[TravelTimeHolder]) {
      case (acc, pte) =>
        val vehicleType = vehicleTypeIdToVehicleType(Id.create(pte.vehicleType, classOf[BeamVehicleType]))
        val duration = (pte.arrivalTime - pte.departureTime).toDouble
        val avgSpeed = if (duration == 0) 0 else pte.legLength / duration
        speeds += avgSpeed

        csvWriter.write(nPTEs, pte.legLength, duration, avgSpeed)

        val linkIds = pte.linkIds.map(lid => linkMap(Id.createLinkId(lid)))
        val freeflowDuration: Double = calcFreeflowDuration(freeFlowTravelTime, linkIds)
        val r5FreeFlowDuration = calcR5Duration_attempt2(
          linkMap,
          freeFlowTravelTime,
          pte.mode,
          pte.departureTime.toDouble,
          vehicleType,
          linkIds
        )
        val r5LinkStatDuration = maybeLinkTravelTimeContainer.map(
          calcR5Duration_attempt2(linkMap, _, pte.mode, pte.departureTime.toDouble, vehicleType, linkIds)
        )
        val (a, b) = (duration - duration * allowedDiffPct / 100, duration + duration * allowedDiffPct / 100)
        val isInside = freeflowDuration >= a && freeflowDuration <= b
        if (!isInside) {
          //println(s"duration: ${duration}, freeflowDuration: $freeflowDuration. ratio: ${duration / freeflowDuration}")
        }
        nPTEs += 1
        TravelTimeHolder(duration, freeflowDuration, r5FreeFlowDuration, r5LinkStatDuration) :: acc
    }

    csvWriter.close()

    val top100Speeds = speeds.sortBy(x => -x).take(100)
    println(s"top100Speeds: $top100Speeds")
    println(s"Speed stats: ${Statistics(speeds)}")
    (nPTEs, travelTimes)
  }

  private def calcFreeflowDuration(freeFlowTravelTime: FreeFlowTravelTime, linkIds: IndexedSeq[Link]): Double = {
    linkIds.foldLeft(0.0) {
      case (acc, link) =>
        val t = freeFlowTravelTime.getLinkTravelTime(link, 0.0, null, null)
        acc + t
    }
  }

  private def calcR5Duration(
    travelTime: TravelTime,
    departureTime: Double,
    vehicleType: BeamVehicleType,
    linkIds: IndexedSeq[Link]
  ): Double = {
    val minSpeed = 1.3
    linkIds
      .foldLeft((0.0, departureTime)) {
        case ((acc, currentTime), link) =>
          val maxSpeed: Double = vehicleType.maxVelocity.getOrElse(2.22) // From R5 constant
          val minTravelTime = (link.getLength / maxSpeed).ceil.toInt
          val maxTravelTime = (link.getLength / minSpeed).ceil.toInt
          val physSimTravelTime = travelTime.getLinkTravelTime(link, currentTime, null, null).ceil.toInt
          val linkTravelTime = Math.max(physSimTravelTime, minTravelTime)
          val t = Math.min(linkTravelTime, maxTravelTime)
          val newDuration = acc + t
          (newDuration, currentTime + newDuration)
      }
      ._1
  }

  private def calcR5Duration_attempt2(
    linkMap: Map[Id[Link], Link],
    travelTime: TravelTime,
    beamMode: BeamMode,
    departureTime: Double,
    vehicleType: BeamVehicleType,
    linkIds: IndexedSeq[Link]
  ): Double = {
    val minSpeed = 1.3
    val travelTimeByLinkCalculator = (time: Double, linkId: Int, streetMode: StreetMode) => {
      val maxSpeed: Double = vehicleType.maxVelocity.getOrElse(2.22) // From R5 constant
      val link = linkMap(Id.create(linkId, classOf[Link]))
      val minTravelTime = (link.getLength / maxSpeed).ceil.toInt
      val maxTravelTime = (link.getLength / minSpeed).ceil.toInt
      val physSimTravelTime = travelTime.getLinkTravelTime(link, time, null, null).ceil.toInt
      val linkTravelTime = Math.max(physSimTravelTime, minTravelTime)
      val t = Math.min(linkTravelTime, maxTravelTime)
      t.toDouble
    }
    val linksTimesAndDistances = RoutingModel.linksToTimeAndDistance(
      linkIds.map(_.getId.toString.toInt),
      departureTime.toInt,
      travelTimeByLinkCalculator,
      toR5StreetMode(beamMode),
      transportNetwork.streetLayer
    )
    val duration = linksTimesAndDistances.travelTimes.tail.sum
    duration
  }
}

//object AverageCarTravelTimeAnalysis {
//  def eventsFilter(event: Event): Boolean = {
//    val attribs = event.getAttributes
//    // We need only PathTraversal
//    val isNeededEvent = event.getEventType == "PathTraversal"
//    isNeededEvent
//  }
//
//  def main(args: Array[String]): Unit = {
//    val eventsFilePath = "D:/Work/beam/MultipleJDEQSim/baseline/40.events.csv.gz"
//    val (events: Iterator[Event], closable: Closeable) = {
//      EventReader.fromCsvFile(eventsFilePath, e => true)
//    }
//    val carTravelTimeHandler = new CarTravelTimeHandler(Map.empty)
//    events.foreach { event =>
//      carTravelTimeHandler.handleEvent(event)
//    }
//    println(carTravelTimeHandler.compute)
//  }
//}
