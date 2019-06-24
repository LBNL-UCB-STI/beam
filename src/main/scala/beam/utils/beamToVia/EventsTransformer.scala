package beam.utils.beamToVia

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonLeavesVehicleEvent}
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.mutable

object EventsTransformer {
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

    paths
  }

  def filterAndFixEvents(
    events: Traversable[Event],
    personIsInterested: String => Boolean,
  ): Traversable[Event] = {

    case class Accumulator(
      filteredEvents: mutable.MutableList[Event] = mutable.MutableList.empty[Event],
      selectedVehicles: mutable.HashSet[String] = mutable.HashSet.empty[String],
      personToVehicle: mutable.Map[String, Option[String]] = mutable.Map.empty[String, Option[String]]
    ) {}

    def getPersonLeavesVehicleEvent(time: Double, person: String, vehicle: String): PersonLeavesVehicleEvent = {
      val personId: Id[Person] = Id.create(person, classOf[Person])
      val vehicleId: Id[Vehicle] = Id.create(vehicle, classOf[Vehicle])
      new PersonLeavesVehicleEvent(time, personId, vehicleId)
    }

    val accumulator = events.foldLeft(Accumulator())((acc, event) => {
      val attributes = event.getAttributes

      event.getEventType match {
        case "PersonEntersVehicle" =>
          val personId = attributes.getOrDefault("person", "")
          if (personIsInterested(personId)) {
            acc.personToVehicle.get(personId) match {
              case Some(Some(prevVehicleId)) =>
                acc.filteredEvents += getPersonLeavesVehicleEvent(event.getTime, personId, prevVehicleId)
              case _ =>
            }

            val vehicleId = attributes.getOrDefault("vehicle", "")
            acc.personToVehicle(personId) = Some(vehicleId)

            acc.filteredEvents += event
            acc.selectedVehicles += vehicleId
          }

        case "PersonLeavesVehicle" =>
          val personId = attributes.getOrDefault("person", "")
          if (personIsInterested(personId)) {
            val vehicleId = attributes.getOrDefault("vehicle", "")
            acc.personToVehicle(personId) = None

            acc.filteredEvents += event
            acc.selectedVehicles -= vehicleId
          }

        case "PathTraversal" =>
          val vehicleId = attributes.getOrDefault("vehicle", "")
          val driver = attributes.getOrDefault("driver", "")
          acc.personToVehicle(driver) = Some(vehicleId)

          if (acc.selectedVehicles.contains(vehicleId)) {
            acc.filteredEvents += event
          } else if (personIsInterested(driver)) {
            acc.filteredEvents += event
            acc.selectedVehicles += vehicleId
          }

        case _ => // acc.filteredEvents += event
      }

      acc
    })

    accumulator.filteredEvents
  }

  def calcTimeLimits(
    events: Traversable[Event],
    timeLimitId: (String, Double) => String
  ): mutable.Map[String, Double] = {
    case class Accumulator(
      limits: mutable.Map[String, Double] = mutable.Map.empty[String, Double],
      eventEnds: mutable.Map[String, (Double, Double)] = mutable.Map.empty[String, (Double, Double)]
    )

    val accumulator = events.foldLeft(Accumulator())((acc, event) => {
      event.getEventType match {
        case "PathTraversal" =>
          val pte = PathTraversalEvent(event)
          val time = event.getTime
          val timeEnd = time + pte.linkTravelTime.sum
          val vehicleId = pte.vehicleId.toString

          acc.eventEnds.get(vehicleId) match {
            case Some((prevEventStart, prevEventEnd)) =>
              if (prevEventEnd > time) {
                val limitId = timeLimitId(vehicleId, prevEventStart)
                acc.limits += (limitId -> time)
              }

            case _ =>
          }

          acc.eventEnds(vehicleId) = (pte.time, timeEnd)

        case "PersonLeavesVehicle" =>
          val attributes = event.getAttributes
          val time = event.getTime
          val vehicleId = attributes.getOrDefault("vehicle","")

          acc.eventEnds.get(vehicleId) match {
            case Some((prevEventStart, prevEventEnd)) =>
              if (prevEventEnd > time) {
                val limitId = timeLimitId(vehicleId, prevEventStart)
                acc.limits += (limitId -> time)
              }

            case _ =>
          }

        case _ =>
      }
      acc
    })

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

              val events = EventsTransformer.transformSingle(pte, vId, limit)

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
                  viaEvents += ViaPersonArrivalEvent(lastPosition.time, lastPosition.vehicleId, lastPosition.linkId)
                case _ =>
              }

              (viaEvents, typeToIdMap, lastVehiclePosition)

            case _ => (viaEvents, typeToIdMap, lastVehiclePosition)
          }
      }

    (removePathDuplicates(viaLinkEvents), typeToIdsMap)
  }
}
