package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPathTraversal}

import scala.collection.mutable

object TransformAllPathTraversal {

  def transformAndWrite(runConfig: RunConfig): Unit = {
    val events = EventsReader
      .fromFile(runConfig.beamEventsPath)
      .getOrElse(Seq.empty[BeamEvent])

    val selectedIds = collectIds(events, runConfig.vehicleSampling)

    val selectedEvents = events.collect {
      case event: BeamPathTraversal if selectedIds.contains(event.vehicleId) => event
    }

    val (pathLinkEvents, typeToIdSeq) = EventsTransformer.transform(selectedEvents)
    Writer.writeViaEvents(pathLinkEvents, runConfig.viaEventsPath)
    Writer.writeViaIdFile(typeToIdSeq, runConfig.viaIdGoupsFilePath)
  }

  def collectIds(events: Traversable[BeamEvent], rules: Seq[VehicleSample]): mutable.HashSet[String] = {
    val allVehicles = events.foldLeft(mutable.Map.empty[String, mutable.HashSet[String]])((vehicles, event) => {
      event match {
        case pte: BeamPathTraversal =>
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
          vehicleIds.foreach(id => {
            if (rule.percentage >= 1.0 || rule.percentage >= Math.random()) selected += id
          })
        case None =>
      }

      selected
    })

    val filteredTypes = mutable.HashSet(rules.map(_.vehicleType): _*)
    allVehicles.foldLeft(selectedIds)((selected, vehiclesGroup) => {
      val (vehicleType: String, ids) = vehiclesGroup
      if (!filteredTypes.contains(vehicleType)) selectedIds ++= ids
      selected
    })
  }
}
