package beam.utils.beamToVia

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event
import scala.collection.mutable

object EventsTransformator {

  private def transformSingle(
    pte: PathTraversalEvent,
    vehicleId: String,
    timeLimit: Option[Double]
  ): Seq[ViaEvent] = {
    val onePiece: Double = timeLimit match {
      case None => 1.0
      case Some(limit) =>
        val sum: Double = pte.linkTravelTime.sum * 1.0
        if (pte.time + sum <= limit) 1.0
        else (limit - pte.time) / sum
    }

    val (_, times) =
      pte.linkTravelTime.foldLeft((pte.time, mutable.MutableList.empty[(Double, Double)])) {
        case ((lastTime, timeList), time) =>
          val linkTime = lastTime + Math.round(time * onePiece)
          timeList += Tuple2(lastTime, linkTime)
          (linkTime, timeList)
      }

    val paths = pte.linkIds
      .zip(times)
      .flatMap {
        case (linkId, timeTuple) =>
          val (enteredTime, leftTime) = timeTuple
          val entered =
            ViaTraverseLinkEvent(enteredTime, vehicleId, EnteredLink, linkId)
          val left = ViaTraverseLinkEvent(leftTime, vehicleId, LeftLink, linkId)
          Seq(entered, left)
      }

    paths //.slice(1, paths.size - 1)
  }

  def filterEvents(
    events: Traversable[Event],
    personIsInterested: String => Boolean,
    vehicleIsInterested: String => Boolean
  ): Traversable[Event] = {
    val (filteredEvents, _) = events
      .foldLeft(
        (
          mutable.MutableList[Event](),
          mutable.HashSet[String]()
        )
      )((accumulator, event) => {
        val (filtered, vehicles) = accumulator
        val attributes = event.getAttributes

        val eventType: String = event.getEventType
        eventType match {
          case "PersonEntersVehicle" =>
            val personId = attributes.getOrDefault("person", "")
            if (personIsInterested(personId)) {
              val vehicleId = attributes.getOrDefault("vehicle", "")
              if (vehicleIsInterested(vehicleId)) {
                vehicles += vehicleId
                filtered += event
              }
            }

          case "PersonLeavesVehicle" =>
            val personId = attributes.getOrDefault("person", "")
            if (personIsInterested(personId)) {
              val vehicleId = attributes.getOrDefault("vehicle", "")
              if (vehicleIsInterested(vehicleId)) {
                vehicles -= vehicleId
                filtered += event
              }
            }

          case "PathTraversal" =>
            val vehicleId = attributes.getOrDefault("vehicle", "")
            if (vehicles.contains(vehicleId)) filtered += event

          case _ =>
        }

        (filtered, vehicles)
      })

    filteredEvents
  }

  def calcTimeLimits(
    events: Traversable[Event],
    timeLimitId: (String, Double) => String
  ): mutable.Map[String, Double] = {
    val (timeLimits, _) =
      events.foldLeft(mutable.Map.empty[String, Double], mutable.Map.empty[String, (Double, Double)]) {
        case ((limits, eventEnds), event) =>
          event.getEventType match {
            case "PathTraversal" =>
              val pte = PathTraversalEvent(event)
              val timeEnd = pte.time + pte.linkTravelTime.sum
              val vId = pte.vehicleId.toString

              eventEnds.get(vId) match {
                case Some((prevEventStart, prevEventEnd)) =>
                  if (prevEventEnd > pte.time) {
                    val limitId = timeLimitId(vId, prevEventStart)
                    limits += (limitId -> pte.time)
                  }
                case _ =>
              }

              eventEnds(vId) = (pte.time, timeEnd)

              (limits, eventEnds)

            case _ => (limits, eventEnds)
          }
      }

    timeLimits
  }

  def removePathDuplicates(events: mutable.MutableList[ViaEvent]): Traversable[ViaEvent] = {
    /*
    duplicate example:
    1. <event time="19925.0" type="entered link"  vehicle="12" link="41"/>
    2. <event time="19930.0" type="left link"     vehicle="12" link="41"/>
    3. <event time="19940.0" type="entered link"  vehicle="12" link="41"/>
    4. <event time="19946.0" type="left link"     vehicle="12" link="41"/>

    it leads into vehicle twitching in Via.
    rows number 2 and 3 should be removed.
     */

    case class VehicleTripHistory(
      vehicleId: String,
      var lastEnteredLink: Option[Int] = None,
      eventsIndexesVisited: mutable.MutableList[Int] = mutable.MutableList.empty[Int],
      eventsIndexesToRemove: mutable.MutableList[Int] = mutable.MutableList.empty[Int]
    ) {
      def visitLink(event: ViaTraverseLinkEvent, eventIndex: Int): Unit = {
        lastEnteredLink match {
          case None                                 => lastEnteredLink = Some(event.link)
          case Some(linkId) if linkId != event.link => moveToLink(Some(event.link))
          case _                                    => eventsIndexesVisited += eventIndex
        }
      }

      def moveToLink(nextLink: Option[Int]): Unit = {
        if (eventsIndexesVisited.length > 1)
          eventsIndexesToRemove ++= eventsIndexesVisited.slice(0, eventsIndexesVisited.length - 1)
        eventsIndexesVisited.clear()
        lastEnteredLink = nextLink
      }
    }

    case class Accumulator(
      history: mutable.Map[String, VehicleTripHistory] = mutable.Map.empty[String, VehicleTripHistory],
      linksToRemove: mutable.HashSet[Int] = mutable.HashSet.empty[Int]
    ) {
      def addHistoryEntry(event: ViaTraverseLinkEvent, eventIndex: Int): Unit = {
        val tripHistory = VehicleTripHistory(event.vehicle)
        tripHistory.visitLink(event, eventIndex)
        history += (event.vehicle -> tripHistory)
      }

      def finishTravel(): Unit = history.values.foreach(_.moveToLink(None))
    }

    val eventsWithIdexes = events.zipWithIndex

    val accumulator = eventsWithIdexes.foldLeft(Accumulator()) {
      case (acc, (event: ViaTraverseLinkEvent, index)) =>
        acc.history.get(event.vehicle) match {
          case Some(history) => history.visitLink(event, index)
          case None          => acc.addHistoryEntry(event, index)
        }

        acc

      case (acc, _) => acc
    }

    accumulator.finishTravel()
    val indexesToRemove = mutable.HashSet.empty[Int]
    accumulator.history.values.foreach(history => indexesToRemove ++= history.eventsIndexesToRemove)

    val filteredEvents = eventsWithIdexes.collect {
      case (event, index) if !indexesToRemove.contains(index) => event
    }

    filteredEvents
  }

  def transform(
    events: Traversable[Event]
  ): (Traversable[ViaEvent], mutable.Map[String, mutable.HashSet[String]]) = {
    def timeLimitId(vehicleId: String, eventTime: Double): String = vehicleId + "_" + eventTime.toString
    def vehicleType(pte: PathTraversalEvent): String =
      pte.mode + "__" + pte.vehicleType + "__P" + "%03d".format(pte.numberOfPassengers)
    def vehicleId(pte: PathTraversalEvent): String = vehicleType(pte) + "__" + pte.vehicleId

    val timeLimits = calcTimeLimits(events, timeLimitId)

    case class LastVehiclePosition(vehicleId: String, linkId: Int, time: Double)

    val (viaLinkEvents, typeToIdsMap, _) = events
      .foldLeft(
        (
          mutable.MutableList.empty[ViaEvent],
          mutable.Map.empty[String, mutable.HashSet[String]],
          mutable.Map.empty[String, LastVehiclePosition]
        )
      ) {
        case ((viaEvents, typeToIdMap, lastVehiclePosition), event) =>
          event.getEventType match {
            case "PathTraversal" =>
              val pte = PathTraversalEvent(event)
              val vType = vehicleType(pte)
              val vId = vehicleId(pte)

              val limitId = timeLimitId(pte.vehicleId.toString, pte.time)
              val limit = timeLimits.get(limitId)

              val events = EventsTransformator.transformSingle(pte, vId, limit)

              typeToIdMap.get(vType) match {
                case Some(mutableSeq) => mutableSeq += vId
                case _ =>
                  typeToIdMap += (vType -> mutable.HashSet[String](vId))
              }

              if (events.nonEmpty)
                lastVehiclePosition(pte.vehicleId.toString) =
                  LastVehiclePosition(vId, events.last.link, events.last.time)

              viaEvents ++= events
              (viaEvents, typeToIdMap, lastVehiclePosition)

            case "PersonLeavesVehicle" =>
              val attributes = event.getAttributes
              val vehicleId = attributes.getOrDefault("vehicle", "")

              lastVehiclePosition.get(vehicleId) match {
                case Some(lastPosition) =>
                  viaEvents += ViaPersonArrivalEvent(lastPosition.time + 1, lastPosition.vehicleId, lastPosition.linkId)
                case _ =>
              }

              (viaEvents, typeToIdMap, lastVehiclePosition)

            case _ => (viaEvents, typeToIdMap, lastVehiclePosition)
          }
      }

    (removePathDuplicates(viaLinkEvents), typeToIdsMap)
  }
}
