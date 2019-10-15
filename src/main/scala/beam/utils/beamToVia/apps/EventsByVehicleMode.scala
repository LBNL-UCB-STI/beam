package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{BeamEventsReader, EventsProcessor, LinkCoordinate, Point, Writer}
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal}
import beam.utils.beamToVia.beamEventsFilter.{MutableSamplingFilter, MutableVehiclesFilter}
import beam.utils.beamToVia.viaEvent.ViaEvent

import scala.collection.mutable

object EventsByVehicleMode extends App {

  // gradle execute -PmainClass=beam.utils.beamToVia.apps.EventsByVehicleMode -PappArgs="['D:/Work/BEAM/history/visualizations/v35.it3.events.csv', 'D:/Work/BEAM/_tmp/output.via.xml', 'car,bus', '0.5', 'D:/Work/BEAM/history/visualizations/physSimNetwork.xml', '548966', '4179000', '500']" -PmaxRAM=16g
  val exampleInputArgs = Seq(
    // Events file path may be in csv or xml format. Does not work with archives.
    "D:/Work/BEAM/history/visualizations/v35.it3.events.csv",
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
    "1000"
  )

  val inputArgs = args

  if (inputArgs.length == 4) {
    val eventsFile = inputArgs(0)
    val outputFile = inputArgs(1)
    val selectedModes = inputArgs(2).split(',').toSeq
    val sampling = inputArgs(3).toDouble

    val filter = MutableVehiclesFilter.withListOfVehicleModes(selectedModes, sampling)
    buildViaFile(eventsFile, outputFile, filter)
  } else if (inputArgs.length == 8) {
    val eventsFile = inputArgs(0)
    val outputFile = inputArgs(1)
    val selectedModes = inputArgs(2).split(',').toSeq
    val sampling = inputArgs(3).toDouble

    val networkPath = inputArgs(4)
    val circleX = inputArgs(5).toInt
    val circleY = inputArgs(6).toInt
    val circleR = inputArgs(7).toInt

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
    Console.print("wrong args");
    Console.print("usage:");
    Console.print("simple sampling args: beamEventsFile outputFile selectedModes sampling");
    Console.print("with circle sampling args: beamEventsFile outputFile selectedModes sampling networkFile X Y R");
  }

  def buildViaFile(eventsFile: String, outputFile: String, filter: MutableSamplingFilter): Unit = {
    def vehicleType(pte: BeamPathTraversal): String = pte.mode + "__" + pte.vehicleType
    def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

    val (vehiclesEvents, _) = EventsProcessor.readWithFilter(eventsFile, filter)
    val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

    Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, outputFile)
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

    val networkXml = xml.XML.loadFile(networkPath)
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

    val vehiclesInCircle = BeamEventsReader
      .fromFileFoldLeft[CircleAccumulator](eventsPath, new CircleAccumulator(), (acc, event) => {
        acc.process(event)
        acc
      })
      .getOrElse(new CircleAccumulator())
      .interestingVehicles

    Console.println("sampled " + vehiclesInCircle.size + " vehicles moved through selected circle")

    MutableVehiclesFilter.withListOfVehicleModesAndSelectedIds(vehiclesInCircle, vehicleModes, sampling)
  }
}
