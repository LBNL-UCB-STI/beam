package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}
import beam.utils.beamToVia.viaEvent.{ViaEvent, ViaPersonArrivalEvent, ViaTraverseLinkEvent}

import scala.collection.mutable

object EventsTransformer {

  /*
 def addMissingPersonLeavesVehicleEvents(
    events: Traversable[BeamEvent],
  ): Traversable[BeamEvent] = {

    case class Accumulator(
      resultingEvents: mutable.MutableList[BeamEvent] = mutable.MutableList.empty[BeamEvent],
      personToVehicle: mutable.Map[String, Option[String]] = mutable.Map.empty[String, Option[String]]
    )

    val accumulator = events.foldLeft(Accumulator()) {
      case (acc, event: BeamPersonEntersVehicle) =>
        acc.personToVehicle.get(event.personId) match {
          case Some(Some(prevVehicleId)) =>
            acc.resultingEvents += BeamPersonLeavesVehicle(event.time, event.personId, prevVehicleId)
          case _ =>
        }

        acc.personToVehicle(event.personId) = Some(event.vehicleId)
        acc.resultingEvents += event

        acc

      case (acc, event: BeamPersonLeavesVehicle) =>
        acc.personToVehicle(event.personId) = None
        acc.resultingEvents += event

        acc

      case (acc, event: BeamPathTraversal) =>
        acc.personToVehicle(event.driverId) = Some(event.vehicleId)
        acc.resultingEvents += event

        acc

      case (acc, _) => acc

    }

    accumulator.resultingEvents
  }
   */

  def calcTimeLimits(
    events: Traversable[BeamEvent],
    timeLimitId: (String, Double) => String
  ): mutable.Map[String, Double] = {
    case class Accumulator(
      limits: mutable.Map[String, Double] = mutable.Map.empty[String, Double],
      eventEnds: mutable.Map[String, (Double, Double)] = mutable.Map.empty[String, (Double, Double)]
    )

    val accumulator = events.foldLeft(Accumulator()) {
      case (acc, event: BeamPathTraversal) =>
        val timeEnd = event.time + event.linkTravelTime.sum
        val vehicleId = event.vehicleId.toString

        acc.eventEnds.get(vehicleId) match {
          case Some((prevEventStart, prevEventEnd)) =>
            if (prevEventEnd > event.time) {
              val limitId = timeLimitId(vehicleId, prevEventStart)
              acc.limits += (limitId -> event.time)
            }

          case _ =>
        }

        acc.eventEnds(vehicleId) = (event.time, timeEnd)

        acc

      case (acc, event: BeamPersonLeavesVehicle) =>
        acc.eventEnds.get(event.vehicleId) match {
          case Some((prevEventStart, prevEventEnd)) =>
            if (prevEventEnd > event.time) {
              val limitId = timeLimitId(event.vehicleId, prevEventStart)
              acc.limits += (limitId -> event.time)
            }

          case _ =>
        }

        acc

      case (acc, _) => acc
    }

    accumulator.limits
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
    events: Traversable[BeamEvent],
    vahicleIsInteresting: String => Boolean
  ): (Traversable[ViaEvent], mutable.Map[String, mutable.HashSet[String]]) = {
    def timeLimitId(vehicleId: String, eventTime: Double): String = vehicleId + "_" + eventTime.toString
    def vehicleType(pte: BeamPathTraversal): String =
      pte.mode + "__" + pte.vehicleType + "__P" + "%03d".format(pte.numberOfPassengers)
    def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

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
          event match {
            case pte: BeamPathTraversal if vahicleIsInteresting(pte.vehicleId) =>
              val vType = vehicleType(pte)
              val vId = vehicleId(pte)

              val limitId = timeLimitId(pte.vehicleId.toString, pte.time)
              val limit = timeLimits.get(limitId)

              val events = pte.toViaEvents(vId, limit)

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

            case plv: BeamPersonLeavesVehicle if vahicleIsInteresting(plv.vehicleId) =>
              lastVehiclePosition.get(plv.vehicleId) match {
                case Some(lastPosition) =>
                  viaEvents += ViaPersonArrivalEvent(lastPosition.time, lastPosition.vehicleId, lastPosition.linkId)
                case _ =>
              }

              (viaEvents, typeToIdMap, lastVehiclePosition)

            case _ => (viaEvents, typeToIdMap, lastVehiclePosition)
          }
      }

    Console.println("got " + viaLinkEvents.size + " via events with possible duplicates")

    (removePathDuplicates(viaLinkEvents), typeToIdsMap)
  }
}
