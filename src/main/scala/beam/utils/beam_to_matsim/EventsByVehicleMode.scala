package beam.utils.beam_to_matsim

import beam.utils.beam_to_matsim.io.{BeamEventsReader, Reader, Writer}
import beam.utils.beam_to_matsim.utils.{LinkCoordinate, Point}
import beam.utils.beam_to_matsim.events.{BeamEvent, BeamPathTraversal}
import beam.utils.beam_to_matsim.events_filter.{MutableSamplingFilter, MutableVehiclesFilter}
import beam.utils.beam_to_matsim.via_event.ViaEvent

import scala.collection.mutable
import scala.xml.XML

object EventsByVehicleMode extends App {

  // gradle execute -PmainClass=beam.utils.beamToVia.EventsByVehicleMode -PappArgs="['D:/Work/BEAM/history/visualizations/v35.it3.events.csv', 'D:/Work/BEAM/_tmp/output.via.xml', 'car,bus', '0.5', 'D:/Work/BEAM/history/visualizations/physSimNetwork.xml', '548966', '4179000', '500']" -PmaxRAM=16g
  val inputArgs1 = Seq(
    // Events file path may be in csv or xml format. Does not work with archives.
    "D:/Work/BEAM/history/visualizations/v33.0.events.csv",
    // output file path
    "D:/Work/BEAM/_tmp/output.via.xml",
    // list of vehicle modes, case insensitive
    "car,bus",
    // 1 means 100%
    "0.5",
    // network path
    "D:/Work/BEAM/history/visualizations/physSimNetwork.xml",
    // san francisco, approximately: x:548966 y:4179000 r:5000
    "548966",
    "4179000",
    "50000"
  )

  // gradle execute -PmainClass=beam.utils.beamToVia.EventsByVehicleMode -PappArgs="['D:/Work/BEAM/history/visualizations/v33.0.events.csv', 'D:/Work/BEAM/_tmp/output.via.xml', 'car,bus', '1']" -PmaxRAM=16g
  val nputArgs2 = Seq(
    // Events file path may be in csv or xml format. Does not work with archives.
    "D:/Work/BEAM/history/visualizations/v33.0.events.csv", //v35.it3.events.csv", //
    // output file path
    "D:/Work/BEAM/_tmp/output.via.xml",
    // list of vehicle modes, case insensitive
    "car,bus",
    // 1 means 100%
    "1"
  )

  val inputArgs = args // inputArgs2

  if (inputArgs.length == 4) {
    val eventsFile = inputArgs.head
    val outputFile = inputArgs(1)
    val selectedModes = inputArgs(2).split(',').toSeq
    val sampling = inputArgs(3).toDouble

    Console.println(s"going to transform BEAM events from $eventsFile and write them into $outputFile")
    Console.println(s"selected modes are: ${selectedModes.mkString(",")} and samling is: $sampling")

    val filter = MutableVehiclesFilter.withListOfVehicleModes(selectedModes, sampling)
    buildViaFile(eventsFile, outputFile, filter)
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

    buildViaFile(eventsFile, outputFile, filter)
  } else {
    Console.print("wrong args")
    Console.print("usage:")
    Console.print("simple sampling args: beamEventsFile outputFile selectedModes sampling")
    Console.print("with circle sampling args: beamEventsFile outputFile selectedModes sampling networkFile X Y R")
  }

  def buildViaFile(eventsFile: String, outputFile: String, filter: MutableSamplingFilter): Unit = {
    Console.println("reading events with vehicles sampling ...")

    def vehicleType(pte: BeamPathTraversal): String = pte.mode + "__" + pte.vehicleType
    def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

    val (vehiclesEvents, _) = Reader.readWithFilter(eventsFile, filter)
    val (events, typeToId) = Reader.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

    Writer.writeViaEventsQueue[ViaEvent](events, _.toXmlString, outputFile)
    Writer.writeViaIdFile(typeToId, outputFile + ".ids.txt")
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

    case class Circle(x: Double, y: Double, r: Double) {
      val rSquare: Double = r * r
    }

    val sfCircle = Circle(circleX, circleY, circleR)
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

    class CircleAccumulator() {
      var interestingVehicles = mutable.HashSet.empty[String]

      def process(event: BeamEvent): Unit = event match {
        case pte: BeamPathTraversal if pte.linkIds.exists(interestingLinks.contains) =>
          interestingVehicles += pte.vehicleId

        case _ =>
      }
    }

    Console.println(s"looking for vehicles which move through circle (X:$circleX Y:$circleY R:$circleR) ...")

    val vehiclesInCircle = BeamEventsReader
      .fromFileFoldLeft[CircleAccumulator](eventsPath, new CircleAccumulator(), (acc, event) => {
        acc.process(event)
        acc
      })
      .getOrElse(new CircleAccumulator())
      .interestingVehicles

    Console.println(s"found ${vehiclesInCircle.size} vehicles moved through the circle")

    MutableVehiclesFilter.withListOfVehicleModesAndSelectedIds(vehiclesInCircle, vehicleModes, sampling)
  }
}
