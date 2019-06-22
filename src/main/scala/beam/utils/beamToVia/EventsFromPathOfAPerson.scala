package beam.utils.beamToVia

import java.io.{File, PrintWriter}

import beam.agentsim.events.PathTraversalEvent
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable

object EventsFromPathOfAPerson extends App {
  //val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml"
  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.csv"

  val outputEventsPath = sourcePath + ".via.pathLinkEvents.xml"
  val outputGroupsPath = sourcePath + ".via.vehicleTypeToIds.txt"

  val events = EventsReader
    .fromFile(sourcePath)
    .getOrElse(Seq.empty[Event])

  def personIsInterested(personId: String): Boolean = {
    //personId == "022802-2012001386215-0-6282252"
    personId == "060700-2013001017578-0-4879259"
  }

  def vehicleIsInterested(vehicleId: String): Boolean = {
    !vehicleId.startsWith("body-") && vehicleId.length > 0
  }

  def logEvent(pw: PrintWriter, event: Event): Unit = {
    pw.println(event.toString)
  }

  val pw = new PrintWriter(new File(outputEventsPath + ".trace.xml"))

  val (pteEvents, _) = events
    .foldLeft(
      (
        mutable.MutableList[PathTraversalEvent](),
        mutable.HashSet[String]()
      )
    )((accumulator, event) => {
      val (ptes, vehicles) = accumulator
      val attributes = event.getAttributes

      val eventType: String = event.getEventType
      eventType match {
        case "PersonEntersVehicle" =>
          val personId = attributes.getOrDefault("person", "")
          if (personIsInterested(personId)) {
            val vehicleId = attributes.getOrDefault("vehicle", "")
            if (vehicleIsInterested(vehicleId)) {
              vehicles += vehicleId

              logEvent(pw, event)
            }
          }

        case "PersonLeavesVehicle" =>
          val personId = attributes.getOrDefault("person", "")
          if (personIsInterested(personId)) {
            val vehicleId = attributes.getOrDefault("vehicle", "")
            if (vehicleIsInterested(vehicleId)) {
              vehicles -= vehicleId

              logEvent(pw, event)
            }
          }

        case "PathTraversal" =>
          val vehicleId = attributes.getOrDefault("vehicle", "")
          if (vehicles.contains(vehicleId)) {
            ptes += PathTraversalEvent(event)

            logEvent(pw, event)
          }
        case _ =>
      }

      (ptes, vehicles)
    })

  pw.close()

  val (pathLinkEvents, typeToIdSeq) = PTETransformator.transformMultiple(pteEvents)
  EventsWriter.write(pathLinkEvents, typeToIdSeq, outputEventsPath, outputGroupsPath)
}
