package beam.utils.beamToVia.apps

import beam.utils.beamToVia.{BeamEventsReader, EventsProcessor, LinkCoordinate, Point, Writer}
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal}
import beam.utils.beamToVia.beamEventsFilter.{MutableSamplingFilter, MutableVehiclesFilter}
import beam.utils.beamToVia.viaEvent.ViaEvent

import scala.collection.mutable

object EventsByVehicleMode extends App {
  val beamEventsFilePath = "D:/Work/BEAM/history/visualizations/v35.it3.events.csv"
  val outputFile = "D:/Work/BEAM/_tmp/output.via.xml"
  val selectedVehiclesModes = Seq[String]("car", "bus")
  val sampling = 0.1

  val networkPath = "D:/Work/BEAM/history/visualizations/physSimNetwork.xml"

  // san francisco, approximately: x:548966 y:4179000 r:5000
  val circleX = 548966
  val circleY = 4179000
  val circleR = 4000

  val filter: MutableSamplingFilter = if (networkPath.nonEmpty)
    getFilterWithCircleSampling(selectedVehiclesModes, sampling, networkPath, beamEventsFilePath, circleX, circleY, circleR)
    else MutableVehiclesFilter.withListOfVehicleModes(selectedVehiclesModes, sampling)

  def vehicleType(pte: BeamPathTraversal): String = pte.mode + "__" + pte.vehicleType
  def vehicleId(pte: BeamPathTraversal): String = vehicleType(pte) + "__" + pte.vehicleId

  val (vehiclesEvents, _) = EventsProcessor.readWithFilter(beamEventsFilePath, filter)
  val (events, typeToId) = EventsProcessor.transformPathTraversals(vehiclesEvents, vehicleId, vehicleType)

  Writer.writeViaEventsQueue[ViaEvent](events, _.toXml.toString, outputFile)
  Writer.writeViaIdFile(typeToId, outputFile + ".ids.txt")

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
