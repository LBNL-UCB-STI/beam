package scripts.beam_to_matsim

import beam.utils.beam_to_matsim.events.{BeamEvent, PathTraversalWithLinks}
import beam.utils.beam_to_matsim.io.{BeamEventsReader, Writer}
import beam.utils.beam_to_matsim.utils.{Circle, LinkCoordinate, Point}

import scala.collection.mutable
import scala.xml.XML

/*
a script to collect ids of all vehicles which move through selected circle
 */

object FindIdsInCircles extends App {

  // format: off
  /************************************************************************************************
    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.FindIdsInCircles -PappArgs="[
      '<beam events csv file>',
      '<vehicles output file>',
      '<physsim network xml file>',
      '<circle x point>', // 548966
      '<circle y point>', // 4179000
      '<circle radius>'   // 5000
    ]" -PmaxRAM=16g
  *************************************************************************************************/
  // format: on

  val eventsPath = args(0)
  val outputPath = args(1)
  val networkPath = args(2)
  val circleX = args(3).toInt
  val circleY = args(4).toInt
  val circleR = args(5).toInt

  val networkXml = XML.loadFile(networkPath)
  val nodes = LinkCoordinate.parseNodes(networkXml)

  val circle = Circle(circleX, circleY, circleR)

  val interestingNodes = nodes
    .foldLeft(mutable.Map.empty[Int, Point]) {
      case (selectedNodes, (nodeId, point)) if circle.contains(point) => selectedNodes += nodeId -> point
      case (selectedNodes, _)                                         => selectedNodes
    }
    .toMap

  val interestingLinks = LinkCoordinate
    .parseNetwork(networkXml, interestingNodes)
    .foldLeft(mutable.HashSet.empty[Int]) { case (links, (linkId, _)) =>
      links += linkId
    }

  class CircleAccumulator() {
    val interestingVehicles = mutable.HashSet.empty[String]

    def process(event: BeamEvent): Unit = event match {
      case pte: PathTraversalWithLinks if pte.linkIds.exists(interestingLinks.contains) =>
        interestingVehicles += pte.vehicleId

      case _ =>
    }
  }

  val vehiclesInCircle = BeamEventsReader
    .fromFileFoldLeft[CircleAccumulator](
      eventsPath,
      new CircleAccumulator(),
      (acc, event) => {
        acc.process(event)
        acc
      }
    )
    .getOrElse(new CircleAccumulator())
    .interestingVehicles

  Writer.writeSeqOfString(vehiclesInCircle, outputPath)
  Console.println("vehicles written into " + outputPath)
}
