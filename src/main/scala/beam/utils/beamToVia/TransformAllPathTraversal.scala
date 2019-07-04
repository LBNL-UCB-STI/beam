package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal}

import scala.collection.mutable

object TransformAllPathTraversal {

  def findVehiclesDrivingThroughCircles(
    events: Traversable[BeamEvent],
    networkPath: String,
    circles: Traversable[Circle]
  ): mutable.HashSet[String] = {
    val networkXml = xml.XML.loadFile(networkPath)
    val nodes = LinkCoordinate.parseNodes(networkXml)

    def pointIsInteresting(point: Point): Boolean =
      circles.exists(circle => point.vithinCircle(circle.x, circle.y, circle.rSquare))

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

    val selectedVehicleIds = events.foldLeft(mutable.HashSet.empty[String]) {
      case (selected, pte: BeamPathTraversal) =>
        if (pte.linkIds.exists(interestingLinks.contains)) selected += pte.vehicleId
        selected
      case (selected, _) =>
        selected
    }

    selectedVehicleIds
  }

  def transformAndWrite(config: RunConfig): Unit = {

    val vehiclesFilter = MutableVehiclesFilter(config.vehicleSampling, config.vehicleSamplingOtherTypes)
    val events = EventsReader
      .fromFileWithFilter(config.beamEventsPath, vehiclesFilter)
      .getOrElse(Seq.empty[BeamEvent])

    val vehicleSelected: String => Boolean =
      if (config.circleFilter.isEmpty) _ => true
      else {
        val selectedIds = findVehiclesDrivingThroughCircles(events, config.networkPath, config.circleFilter)
        id =>
          selectedIds.contains(id)
      }

    val (pathLinkEvents, typeToIdSeq) = EventsTransformer.transform(events, vehicleId => vehicleSelected(vehicleId))

    Writer.writeViaEvents(pathLinkEvents, config.viaEventsPath)
    Writer.writeViaIdFile(typeToIdSeq, config.viaIdGoupsFilePath)
  }
}
