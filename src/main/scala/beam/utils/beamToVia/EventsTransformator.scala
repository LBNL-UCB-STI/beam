package beam.utils.beamToVia

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable

object EventsTransformator {

  private def transformSingle(
    pte: PathTraversalEvent,
    vehicleId: String,
    timeLimit: Option[Double]
  ): Seq[PathLinkEvent] = {
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
        case (id, timeTuple) =>
          val (enteredTime, leftTime) = timeTuple
          val entered =
            PathLinkEvent(enteredTime, "entered link", vehicleId, id)
          val left = PathLinkEvent(leftTime, "left link", vehicleId, id)
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

  def transform(
    events: Traversable[Event]
  ): (Traversable[PathLinkEvent], mutable.Map[String, mutable.HashSet[String]]) = {
    def timeLimitId(vehicleId: String, eventTime: Double): String = vehicleId + "_" + eventTime.toString
    def vehicleType(pte: PathTraversalEvent): String = pte.mode + "__" + pte.vehicleType
    def vehicleId(pte: PathTraversalEvent): String = vehicleType(pte) + "__" + pte.vehicleId

    val timeLimits = calcTimeLimits(events, timeLimitId)

    val (viaLinkEvents, typeToIdsMap, _) = events
      .foldLeft(
        (
          mutable.MutableList.empty[PathLinkEvent],
          mutable.Map.empty[String, mutable.HashSet[String]],
          mutable.Map.empty[String, (String, Int)]
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

              viaEvents ++= EventsTransformator.transformSingle(pte, vId, limit)

              typeToIdMap.get(vType) match {
                case Some(mutableSeq) => mutableSeq += vId
                case _ =>
                  typeToIdMap += (vType -> mutable.HashSet[String](vId))
              }

              //if (pte.linkIds.nonEmpty) lastVehiclePosition(pte.vehicleId.toString) = (vId, pte.linkIds.last)

              (viaEvents, typeToIdMap, lastVehiclePosition)

            /*case "PersonLeavesVehicle" =>
              val attributes = event.getAttributes
              val vehicleId = attributes.getOrDefault("vehicle", "")

              lastVehiclePosition.get(vehicleId) match {
                case Some((vId, lastVisitedLink)) =>
                  viaEvents += PathLinkEvent(event.getTime, "arrival", vId, lastVisitedLink)
                case _ =>
              }

              (viaEvents, typeToIdsMap, lastVehiclePosition)*/

            case _ => (viaEvents, typeToIdMap, lastVehiclePosition)
          }
      }

    (viaLinkEvents, typeToIdsMap)
  }
}
