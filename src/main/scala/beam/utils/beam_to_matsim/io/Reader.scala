package beam.utils.beam_to_matsim.io

import beam.utils.beam_to_matsim.events.{BeamActivityEnd, BeamActivityStart, BeamModeChoice, BeamPathTraversal}
import beam.utils.beam_to_matsim.events_filter.{MutableSamplingFilter, PersonEvents, VehicleTrip}
import beam.utils.beam_to_matsim.via_event._

import scala.collection.mutable

object Reader {

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
      @SuppressWarnings(Array("UnsafeTraversableMethods"))
      val pteSeqHead = pteSeq.head
      pteSeq.drop(1).foldLeft(pteSeqHead) {
        case (prevPTE, currPTE) if prevPTE.linkIds.nonEmpty && currPTE.linkIds.nonEmpty =>
          // if they overlap each other in case of time
          val timeDiff = currPTE.time - prevPTE.arrivalTime
          if (timeDiff < 0) prevPTE.adjustTime(timeDiff)

          // if they overlap each other in case of travel links
          if (prevPTE.linkIds.lastOption == currPTE.linkIds.headOption) {
            currPTE.removeHeadLinkFromTrip()
            @SuppressWarnings(Array("UnsafeTraversableMethods"))
            val removedLinkTime = currPTE.linkTravelTime.head
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

    Console.println("fixing overlapped events ...")
    val progress = new ConsoleProgress("events fixed", vehiclesTrips.size + personsTrips.size, 34)

    vehiclesTrips.foreach { vehicleTrip =>
      progress.step()
      vehicleTrip match {
        case trip if trip.trip.size > 1 => pteOverlappingFix(trip.trip)
        case _                          =>
      }
    }

    personsTrips.foreach { vehicleTrip =>
      progress.step()
      vehicleTrip match {
        case trip if trip.trip.size > 1 => pteOverlappingFix(trip.trip)
        case _                          =>
      }
    }

    progress.finish()

    Console.println(vehiclesTrips.size + " vehicle trips collected")
    Console.println(personsEvents.size + " persons trips collected")

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

    Console.println(s"transforming ${vehiclesTrips.size} trips into MATSIM format ...")
    val progress = new ConsoleProgress("trips transformed", vehiclesTrips.size, 17)

    val viaEventsCollector =
      vehiclesTrips.foldLeft(ViaEventsCollector(vehicleId, vehicleType))((acc, trip) => {
        progress.step()
        acc.collectVehicleTrip(trip.trip)
        acc
      })

    progress.finish()

    Console.println(viaEventsCollector.events.size + " via events with vehicles trips")
    Console.println(viaEventsCollector.vehicleTypeToId.size + " vehicle types")

    (viaEventsCollector.events, viaEventsCollector.vehicleTypeToId)
  }
}
