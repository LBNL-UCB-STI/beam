package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.{
  BeamActivityEnd,
  BeamActivityStart,
  BeamEvent,
  BeamModeChoice,
  BeamPathTraversal,
  BeamPersonEntersVehicle,
  BeamPersonLeavesVehicle
}
import beam.utils.beamToVia.beamEventsFilter.{MutableSamplingFilter, PersonEvents, VehicleTrip}
import beam.utils.beamToVia.viaEvent.{
  EnteredLink,
  LeftLink,
  ViaActivity,
  ViaEvent,
  ViaPersonArrivalEvent,
  ViaPersonDepartureEvent,
  ViaTraverseLinkEvent
}

import scala.collection.mutable

object EventsProcessor {

  def readWithFilter(
    eventsPath: String,
    filter: MutableSamplingFilter
  ): (Traversable[VehicleTrip], Traversable[PersonEvents]) = {

    val beamEventsFilter = BeamEventsReader
      .fromFileFoldLeft[MutableSamplingFilter](eventsPath, filter, (f, ev) => {
        f.filter(ev)
        f
      })
      .getOrElse(filter)

    // fix overlapping of path traversal events for vehicle
    def pteOverlappingFix(pteSeq: Seq[BeamPathTraversal]): Unit = {
      pteSeq.tail.foldLeft(pteSeq.head) {
        case (prevPTE, currPTE) if prevPTE.linkIds.nonEmpty && currPTE.linkIds.nonEmpty =>
          // if they overlap each other in case of time
          val timeDiff = currPTE.time - prevPTE.arrivalTime
          if (timeDiff < 0) prevPTE.adjustTime(timeDiff)

          // if they overlap each other in case of travel links
          if (prevPTE.linkIds.last == currPTE.linkIds.head) {
            val removedLinkTime = currPTE.linkTravelTime.head
            currPTE.removeHeadLinkFromTrip()

            if (currPTE.linkIds.nonEmpty) currPTE.adjustTime(removedLinkTime)
            else prevPTE.adjustTime(removedLinkTime)
          }

          currPTE

        case (_, pte) => pte
      }
    }

    val vehiclesTrips = beamEventsFilter.vehiclesTrips
    val personsTrips = beamEventsFilter.personsTrips
    val personsEvents = beamEventsFilter.personsEvents

    if (vehiclesTrips.nonEmpty)
      vehiclesTrips.foreach {
        case trip if trip.trip.size > 1 => pteOverlappingFix(trip.trip)
        case _                          =>
      }

    if (personsTrips.nonEmpty)
      personsTrips.foreach {
        case trip if trip.trip.size > 1 => pteOverlappingFix(trip.trip)
        case _                          =>
      }

    Console.println(vehiclesTrips.size + " vehicle selected")
    Console.println(personsEvents.size + " persons selected")

    (vehiclesTrips, personsEvents)
  }

  def transformActivities(
    personsEvents: Traversable[PersonEvents]
  ): (mutable.MutableList[ViaEvent], mutable.HashMap[String, Int]) = {

    val viaEvents = mutable.MutableList.empty[ViaEvent]
    val actTypes = mutable.HashMap.empty[String, Int]

    def getActType(acttivityType: String) = "activity_" + acttivityType

    def calcActivities(actType: String): Unit = {
      actTypes.get(actType) match {
        case Some(cnt) => actTypes(actType) = cnt + 1
        case None      => actTypes(actType) = 1
      }
    }

    personsEvents.foreach(_.events.foldLeft(viaEvents)((events, event) => {
      event match {
        case activity: BeamActivityStart =>
          val actType = getActType(activity.activityType)
          calcActivities(actType)
          viaEvents += ViaActivity.start(activity.time, activity.personId, activity.linkId, actType)

        case activity: BeamActivityEnd =>
          val actType = getActType(activity.activityType)
          calcActivities(actType)
          viaEvents += ViaActivity.end(activity.time, activity.personId, activity.linkId, actType)

        case _ =>
      }

      events
    }))

    Console.println(viaEvents.size + " via events for activities display (" + actTypes.size + " different types)")

    (viaEvents, actTypes)
  }

  def transformModeChoices(
    personsEvents: Traversable[PersonEvents],
    modeChoiceDuration: Int = 50
  ): (mutable.MutableList[ViaEvent], mutable.HashMap[String, Int]) = {

    val viaEvents = mutable.MutableList.empty[ViaEvent]
    val modes = mutable.HashMap.empty[String, Int]

    personsEvents.foreach(_.events.foldLeft(viaEvents) {
      case (events, mc: BeamModeChoice) =>
        val actionName = "modeChoice_" + mc.mode

        modes.get(actionName) match {
          case Some(cnt) => modes(actionName) = cnt + 1
          case None      => modes(actionName) = 1
        }

        events += ViaActivity.start(mc.time, mc.personId, mc.linkId, actionName)
        events += ViaActivity.end(mc.time + modeChoiceDuration, mc.personId, mc.linkId, actionName)
        events

      case (acc, _) => acc
    })

    Console.println(viaEvents.size + " via events for modeChoices display (" + modes.size + " different types)")

    (viaEvents, modes)
  }

  def transformPathTraversals(
    vehiclesTrips: Traversable[VehicleTrip],
    vehicleId: BeamPathTraversal => String,
    vehicleType: BeamPathTraversal => String
  ): (mutable.PriorityQueue[ViaEvent], mutable.Map[String, mutable.HashSet[String]]) = {

    case class ViaEventsCollector(
      vehicleId: BeamPathTraversal => String,
      vehicleType: BeamPathTraversal => String,
      events: mutable.PriorityQueue[ViaEvent] =
        mutable.PriorityQueue.empty[ViaEvent]((e1, e2) => e2.time.compare(e1.time)),
      vehicleTypeToId: mutable.Map[String, mutable.HashSet[String]] = mutable.Map.empty[String, mutable.HashSet[String]]
    ) {
      def collectVehicleTrip(ptEvents: Seq[BeamPathTraversal]): Unit = {
        val minTimeStep = 0.0001
        val minTimeIntervalForContinuousMovement = 40.0

        case class EventsTransformer(
          events: mutable.PriorityQueue[ViaEvent],
          var prevEvent: Option[ViaTraverseLinkEvent] = None,
        ) {
          def addPTEEvent(curr: ViaTraverseLinkEvent): Unit = {
            prevEvent match {
              case Some(prev) =>
                if (prev.time >= curr.time) {
                  curr.time = prev.time + minTimeStep
                }

                if ((curr.time - prev.time > minTimeIntervalForContinuousMovement &&
                    curr.eventType == EnteredLink &&
                    prev.eventType == LeftLink) || (prev.vehicle != curr.vehicle)) {

                  if (curr.time - prev.time < minTimeIntervalForContinuousMovement) addPersonArrival(curr.time, prev)
                  else addPersonArrival(prev.time, prev)

                  curr.time += minTimeStep
                  addPersonDeparture(curr)
                  curr.time += minTimeStep * 2
                }

              case _ =>
                curr.time += 0.5
                addPersonDeparture(curr)
                curr.time += minTimeStep * 2
            }

            prevEvent = Some(curr)
            events.enqueue(curr)
          }

          def addPersonArrival(time: Double, viaEvent: ViaTraverseLinkEvent): Unit =
            events.enqueue(ViaPersonArrivalEvent(time + minTimeStep, viaEvent.vehicle, viaEvent.link))

          def addPersonDeparture(viaEvent: ViaTraverseLinkEvent): Unit =
            events.enqueue(ViaPersonDepartureEvent(viaEvent.time + minTimeStep, viaEvent.vehicle, viaEvent.link))
        }

        val transformer = ptEvents.foldLeft(EventsTransformer(events))((acc, pte) => {
          val vId = vehicleId(pte)
          pte.toViaEvents(vId, None).foreach(acc.addPTEEvent)

          val vType = vehicleType(pte)
          vehicleTypeToId.get(vType) match {
            case Some(ids) => ids += vId
            case None      => vehicleTypeToId(vType) = mutable.HashSet(vId)
          }

          acc
        })

        transformer.prevEvent match {
          case Some(event) => transformer.addPersonArrival(event.time, event)
          case _           =>
        }
      }
    }

    val viaEventsCollector =
      vehiclesTrips.foldLeft(ViaEventsCollector(vehicleId, vehicleType))((acc, trip) => {
        acc.collectVehicleTrip(trip.trip)
        acc
      })

    Console.println(viaEventsCollector.events.size + " via events with vehicles trips")
    Console.println(viaEventsCollector.vehicleTypeToId.size + " vehicle types")

    (viaEventsCollector.events, viaEventsCollector.vehicleTypeToId)
  }

  /*
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

  def removePathDuplicates(events: Seq[ViaEvent]): Traversable[ViaEvent] = {
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
    vahicleIsInteresting: String => Boolean,
    idPrefix: String
  ): (Seq[ViaEvent], mutable.Map[String, mutable.HashSet[String]]) = {
    def vehicleCategory(numberOfPassengers: Int): String = "_VC" + (numberOfPassengers / 5)
    def vehiclePassengers(numberOfPassengers: Int): String = "_P%03d".format(numberOfPassengers)

    def timeLimitId(vehicleId: String, eventTime: Double): String = vehicleId + "_" + eventTime.toString
    def vehicleType(pte: BeamPathTraversal): String = {
      val vCat = vehicleCategory(pte.numberOfPassengers)
      val vPas = vehiclePassengers(pte.numberOfPassengers)
      pte.mode + "_" + pte.vehicleType + vPas + vCat
    }

    def vehicleId(pte: BeamPathTraversal): String = idPrefix + vehicleType(pte) + "__" + pte.vehicleId

    val timeLimits = calcTimeLimits(events, timeLimitId)

    case class LastVehiclePosition(vehicleId: String, linkId: Int, time: Double)

    val (viaLinkEvents, typeToIdsMap, _) = events
      .foldLeft(
        (
          mutable.MutableList
            .empty[ViaEvent], // mutable.PriorityQueue.empty[ViaEvent]((e1,e2) => e2.time.compare(e1.time)),
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

    (viaLinkEvents, typeToIdsMap)
  }*/
}
