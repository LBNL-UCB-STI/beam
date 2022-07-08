package scripts.beam_to_matsim

import beam.utils.beam_to_matsim.events.{BeamEvent, PathTraversalWithLinks}
import beam.utils.beam_to_matsim.events_filter.{MutableSamplingFilter, MutableVehiclesFilter}
import beam.utils.beam_to_matsim.io.{BeamEventsReader, Utils}
import beam.utils.beam_to_matsim.utils.{Circle, LinkCoordinate, Point}

import scala.collection.mutable
import scala.xml.XML

/*
a script to generate Via events for specific modes.
 */

object EventsByVehicleMode extends App {

  // format: off
  /**********************************************************************************************************
    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.via.EventsByVehicleMode -PappArgs="[
      '<beam events csv file>',
      '<via events output xml file>',
      '<mode1>,<mode2>',
      '<sampling value>',
    ]" -PmaxRAM=16g

  ************************************************************************************************************

    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.EventsByVehicleMode -PappArgs="[
      '<beam events csv file>',
      '<via events output xml file>',
      '<mode1>,<mode2>',
      '<sampling value>',
      '<physsim network xml file>',
      '<circle x point>', // 548966
      '<circle y point>', // 4179000
      '<circle radius>'   // 5000
    ]" -PmaxRAM=16g
    ********************************************************************************************************/
  // format: on

  val inputArgs = args

  if (inputArgs.length == 4) {
    val eventsFile = inputArgs.head
    val outputFile = inputArgs(1)
    val selectedModes = inputArgs(2).split(',').toSeq
    val sampling = inputArgs(3).toDouble

    Console.println(s"going to transform BEAM events from $eventsFile and write them into $outputFile")
    Console.println(s"selected modes are: ${selectedModes.mkString(",")} and samling is: $sampling")

    val filter = MutableVehiclesFilter.withListOfVehicleModes(selectedModes, sampling)
    Utils.buildViaFile(eventsFile, outputFile, filter)
  } else if (inputArgs.length == 8) {
    val eventsFile = inputArgs.head
    val outputFile = inputArgs(1)
    val selectedModes = inputArgs(2).split(',').toSeq
    val sampling = inputArgs(3).toDouble

    Console.println(s"going to transform BEAM events from $eventsFile and write them into $outputFile")
    Console.println(s"selected modes are: ${selectedModes.mkString(",")} and samling is: $sampling")

    val networkPath = inputArgs(4)
    val circleX = inputArgs(5).toInt
    val circleY = inputArgs(6).toInt
    val circleR = inputArgs(7).toInt

    Console.println(s"also will be used only cars that moved through circle (X:$circleX Y:$circleY R:$circleR)")
    Console.println(s"coordinates of network will be read from $networkPath")

    val filter: MutableSamplingFilter = getFilterWithCircleSampling(
      selectedModes,
      sampling,
      networkPath,
      eventsFile,
      circleX,
      circleY,
      circleR
    )

    Utils.buildViaFile(eventsFile, outputFile, filter)
  } else {
    Console.print("wrong args")
    Console.print("usage:")
    Console.print("simple sampling args: beamEventsFile outputFile selectedModes sampling")
    Console.print("with circle sampling args: beamEventsFile outputFile selectedModes sampling networkFile X Y R")
  }

  def getFilterWithCircleSampling(
    vehicleModes: Seq[String],
    sampling: Double,
    networkPath: String,
    eventsPath: String,
    circleX: Double,
    circleY: Double,
    circleR: Double
  ): MutableSamplingFilter = {

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

    Console.println(s"looking for vehicles which move through circle (X:$circleX Y:$circleY R:$circleR) ...")

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

    Console.println(s"found ${vehiclesInCircle.size} vehicles moved through the circle")

    MutableVehiclesFilter.withListOfVehicleModesAndSelectedIds(vehiclesInCircle, vehicleModes, sampling)
  }
}
