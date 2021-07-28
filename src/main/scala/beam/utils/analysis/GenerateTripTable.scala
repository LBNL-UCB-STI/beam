package beam.utils.analysis

import java.io.Closeable

import beam.utils.EventReader
import beam.utils.csv.CsvWriter
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.collection.mutable

object GenerateTripTable {

  def main(args: Array[String]): Unit = {

    val eventsFile = args(0) //"/home/rajnikant/Downloads/0.events.csv"
    val networkFile = args(1) //"/home/rajnikant/Downloads/outputNetwork.xml.gz"
    val outputPath = args(2) //"/home/rajnikant/IdeaProjects/beam/output/beamville/"
    val outputFile = "personMilesTravelled.csv"

    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network).readFile(networkFile)
    val links = network.getLinks

    val (events: Iterator[Event], closable: Closeable) = EventReader.fromCsvFile(eventsFile, _ => true)

    val filterEvent = events.map {
      case event if event.getEventType == "actstart" | event.getEventType == "actend" =>
        val link = event.getAttributes.get("link")
        val linkId = Id.create[Link](link, classOf[Link])
        val nodeLink = links.get(linkId)
        val fromCoord = nodeLink.getFromNode.getCoord
        val toCoord = nodeLink.getToNode.getCoord
        val (centerX, centerY) = linkCenter(fromCoord, toCoord)
        event.getAttributes.put("center_x", String.valueOf(centerX))
        event.getAttributes.put("center_y", String.valueOf(centerY))
        event
      case event => event
    } //.filterNot(event => event.getAttributes.get("person") == null || event.getAttributes.get("person").contains("rideHail") || event.getAttributes.get("person").contains("Driver"))

    val headers = IndexedSeq(
      "",
      "personId",
      "legId",
      "time",
      "mode",
      "numberOfReplannings",
      "legDurationBasedOnActEndActStart",
      "distanceTravelledPathTraversal",
      "euclideanDistanceToPreviousAct",
      "previousActType"
    )
    val csvWriter = new CsvWriter(s"$outputPath$outputFile", headers)
    writeCSVRows(filterEvent, csvWriter)
    closable.close()
  }

  def writeCSVRows(filterRows: Iterator[Event], csvWriter: CsvWriter): Unit = {
    val personStates = new mutable.HashMap[String, Person]
    val vehicles = new mutable.HashMap[String, mutable.Set[String]]
    var count = 0
    filterRows.foreach { event =>
      val attributes = event.getAttributes
      val eventType = attributes.get("type")
      val personId = attributes.get("person")
      val vehicleId = attributes.get("vehicle")
      val time = attributes.get("time").toDouble

      if (count % 10000 == 0)
        println(s"processing row $count")

      val person = personStates.getOrElse(personId, Person())
      eventType match {
        case "Replanning" =>
          personStates(personId) = person.copy(numberOfReplannings = person.numberOfReplannings + 1)

        case "ModeChoice" =>
          personStates(personId) = person.copy(currentMode = attributes.get("mode"))

        case "actstart" =>
          val linkCenterX = attributes.get("center_x").toDouble
          val linkCenterY = attributes.get("center_y").toDouble
          csvWriter.writeRow(
            IndexedSeq(
              count,
              personId,
              person.legId,
              time,
              person.currentMode,
              person.numberOfReplannings,
              time - person.lastActEndTime,
              person.distanceTravelledPathTraversal,
              distance(person.last_act_x, person.last_act_y, linkCenterX, linkCenterY),
              person.previousActType
            )
          )
          count = count + 1
          val newPerson = Person(legId = person.legId + 1)
          personStates(personId) = newPerson

        case "actend" =>
          val linkCenterX = attributes.get("center_x").toDouble
          val linkCenterY = attributes.get("center_y").toDouble
          val actType = attributes.get("actType")
          personStates(personId) = person.copy(
            lastActEndTime = time,
            last_act_x = linkCenterX,
            last_act_y = linkCenterY,
            previousActType = actType
          )

        case "PersonEntersVehicle" if !(personId.contains("rideHail") || personId.contains("Driver")) =>
          personStates(personId) = person.copy(lastTimePersonEnteredVehicle = time, currentVehicle = vehicleId)
          val persons = vehicles.getOrElse(vehicleId, mutable.Set())
          vehicles(vehicleId) = persons + personId

        case "PersonLeavesVehicle" =>
          personStates(personId) = person.copy(lastTimePersonEnteredVehicle = time.toInt, currentVehicle = "")
          val persons = vehicles.getOrElse(vehicleId, mutable.Set())
          if (persons.contains(personId)) {
            vehicles(vehicleId) = persons - personId
          }

        case "PathTraversal" =>
          val length = attributes.get("length").toDouble
          if (vehicleId.contains("body")) {
            val personId = vehicleId.split('-')(1)
            val person = personStates.getOrElse(personId, Person())
            val distanceTravelledPathTraversal = person.distanceTravelledPathTraversal + length
            personStates(personId) = person.copy(distanceTravelledPathTraversal = distanceTravelledPathTraversal)
          } else {
            val persons = vehicles.getOrElse(vehicleId, mutable.Set())
            persons.foreach(personId => {
              val person = personStates.getOrElse(personId, Person())
              val distanceTravelledPathTraversal = person.distanceTravelledPathTraversal + length
              personStates(personId) = person.copy(distanceTravelledPathTraversal = distanceTravelledPathTraversal)
            })
          }

        case _ =>
      }
    }
    csvWriter.close()
  }

  def distance(x1: Double, y1: Double, x2: Double, y2: Double): Double =
    math.sqrt(math.pow(x1 - x2, 2) + math.pow(y1 - y2, 2))
  def linkCenter(from: Coord, to: Coord): (Double, Double) = ((from.getX + to.getX) / 2, (from.getY + to.getY) / 2)
}

case class Person(
  lastTimePersonEnteredVehicle: Double = -1,
  lastTimePersonLeftVehicle: Double = -1,
  lastTimePersonEnteredBodyVehicle: Double = -1,
  lastActEndTime: Double = -1,
  numberOfReplannings: Int = 0,
  distanceTravelledPathTraversal: Double = 0,
  durationTripBasedOnPathTraversal: Int = 0,
  last_act_x: Double = 0,
  last_act_y: Double = 0,
  legId: Int = 0,
  currentMode: String = "",
  previousActType: String = "",
  firstPathTraversalDepartureTime: Int = -1,
  lastPathTraversalArrivalTime: Int = -1,
  currentVehicle: String = ""
)
