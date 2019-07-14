package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{BeamEventsReader, LinkCoordinate, Point, Writer}
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal, BeamPersonEntersVehicle}
import beam.utils.beamToVia.beamEventsFilter.MutableSamplingFilter

import scala.collection.mutable

object FindIdsInCircles extends App {
  val sourcePath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv"
  val outputPath = "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half_in_SF"
  val networkPath = "D:/Work/BEAM/visualizations/physSimNetwork.xml"

  val networkXml = xml.XML.loadFile(networkPath)
  val nodes = LinkCoordinate.parseNodes(networkXml)

  case class Circle(x: Double, y: Double, r: Double) {
    val rSquare: Double = r * r
  }

  val sfCircle = Circle(548966, 4179000, 5000)
  def pointIsInteresting(point: Point): Boolean = point.vithinCircle(sfCircle.x, sfCircle.y, sfCircle.rSquare)

  val interestingNodes = nodes
    .foldLeft(mutable.Map.empty[Int, Point]) {
      case (selectedNodes, (nodeId, point)) if pointIsInteresting(point) => selectedNodes += nodeId -> point
      case (selectedNodes, _)                                            => selectedNodes
    }
    .toMap

  val interestingLinks = LinkCoordinate
    .parseNetwork(networkXml, interestingNodes)
    .foldLeft(mutable.HashSet.empty[Int]) {
      case (links, (linkId, _)) => links += linkId
    }

/*  object CircleFilter extends MutableSamplingFilter {
    var interestingVehicles = mutable.HashSet.empty[String]
    val empty = Seq.empty[BeamEvent]
    override def filter(event: BeamEvent): Seq[BeamEvent] = event match {
      case pte: BeamPathTraversal =>
        if (pte.linkIds.exists(interestingLinks.contains)) {
          interestingVehicles += pte.vehicleId
          Seq(pte)
        } else {
          empty
        }

      case _ => Seq(event)
    }
  }

  val events = BeamEventsReader
    .fromFileWithFilter(sourcePath, CircleFilter)
    .getOrElse(Seq.empty[BeamEvent])

  case class Accumulator(
    persons: mutable.HashSet[String] = mutable.HashSet.empty[String]
  )

  def vehicleSelected(vehicleId: String): Boolean = CircleFilter.interestingVehicles.contains(vehicleId)

  val accumulator = events.foldLeft(Accumulator())((acc, event) => {
    event match {
      case pev: BeamPersonEntersVehicle if vehicleSelected(pev.vehicleId) => acc.persons += pev.personId
      case _                                                              =>
    }

    acc
  })

  val personsPath = outputPath + ".persons.txt"
  Writer.writeSeqOfString(accumulator.persons, personsPath)
  Console.println("persons written into " + personsPath)

  val vehiclesPath = outputPath + ".vehicles.txt"
  Writer.writeSeqOfString(CircleFilter.interestingVehicles, vehiclesPath)
  Console.println("vehicles written into " + vehiclesPath)*/
}
