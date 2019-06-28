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

  def transformAndWrite(runConfig: RunConfig): Unit = {
    val events = EventsReader
      .fromFile(runConfig.beamEventsPath)
      .getOrElse(Seq.empty[BeamEvent])

    val vehicleMovedTroughCircles: String => Boolean =
      if (runConfig.circleFilter.isEmpty) (_) => true
      else {
        val selectedIds = findVehiclesDrivingThroughCircles(events, runConfig.networkPath, runConfig.circleFilter)
        vehicleId =>
          selectedIds.contains(vehicleId)
      }

    val selectedIds =
      collectIds(events, runConfig.vehicleSampling, runConfig.vehicleSamplingOtherTypes, vehicleMovedTroughCircles)

    val selectedEvents = events.collect {
      case event: BeamPathTraversal if selectedIds.contains(event.vehicleId) => event
    }

    val (pathLinkEvents, typeToIdSeq) = EventsTransformer.transform(selectedEvents)
    Writer.writeViaEvents(pathLinkEvents, runConfig.viaEventsPath)
    Writer.writeViaIdFile(typeToIdSeq, runConfig.viaIdGoupsFilePath)
  }

  def collectIds(
    events: Traversable[BeamEvent],
    rules: Seq[VehicleSample],
    otherVehicleTypesSamples: Double,
    vehicleMovedTroughCircles: String => Boolean
  ): mutable.HashSet[String] = {
    val allVehicles = events.foldLeft(mutable.Map.empty[String, mutable.HashSet[String]])((vehicles, event) => {
      event match {
        case pte: BeamPathTraversal if vehicleMovedTroughCircles(pte.vehicleId) =>
          vehicles.get(pte.vehicleType) match {
            case Some(map) => map += pte.vehicleId
            case None      => vehicles(pte.vehicleType) = mutable.HashSet(pte.vehicleId)
          }
        case _ =>
      }
      vehicles
    })

    val selectedIds = rules.foldLeft(mutable.HashSet.empty[String])((selected, rule) => {
      allVehicles.get(rule.vehicleType) match {
        case Some(vehicleIds) =>
          vehicleIds.foreach(
            id => if (vehicleMovedTroughCircles(id) && rule.percentage >= Math.random()) selected += id
          )
        case None =>
      }

      selected
    })

    val filteredTypes = mutable.HashSet(rules.map(_.vehicleType): _*)
    allVehicles.foldLeft(selectedIds)((selected, vehiclesGroup) => {
      val (vehicleType: String, ids) = vehiclesGroup
      if (!filteredTypes.contains(vehicleType))
        ids.foreach(id => if (otherVehicleTypesSamples >= Math.random()) selected += id)
      selected
    })
  }
}
