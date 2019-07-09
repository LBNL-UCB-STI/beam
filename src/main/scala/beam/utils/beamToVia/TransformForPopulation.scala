package beam.utils.beamToVia

import beam.utils.beamToVia.EventsTransformer.removePathDuplicates
import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}
import beam.utils.beamToVia.viaEvent.ViaEvent

import scala.collection.mutable
import scala.xml.XML

object TransformForPopulation {

  def transformAndWrite(config: RunConfig): Unit = {
    val populationFilter = MutablePopulationFilter(config.populationSampling)

    val events = BeamEventsReader
      .fromFileWithFilter(config.beamEventsPath, populationFilter)
      .getOrElse(Seq.empty[BeamEvent])

    val personMovedThroughCircles: String => Boolean =
      if (config.circleFilter.isEmpty) _ => true
      else {
        val vehiclesMovedThroughCircles =
          TransformAllPathTraversal.findVehiclesDrivingThroughCircles(events, config.networkPath, config.circleFilter)

        val selectedPersons = events.foldLeft(mutable.HashSet.empty[String]) {
          case (selected, pev: BeamPersonEntersVehicle) =>
            if (vehiclesMovedThroughCircles.contains(pev.vehicleId)) selected += pev.personId
            selected
          case (selected, _) => selected
        }

        personId =>
          selectedPersons.contains(personId)
      }

    val vehiclesIds = events.foldLeft(mutable.HashSet.empty[String])((ids, event) => {
      event match {
        case pev: BeamPersonEntersVehicle if personMovedThroughCircles(pev.personId) => ids += pev.vehicleId
        case _                                                                       => ids
      }
    })

    Console.println(populationFilter.toString)
    Console.println(vehiclesIds.size + " interesting vehicles")

    val (viaEventsWithDuplicates, typeToIdSeq) = EventsTransformer.transform(events, vehicleId => vehiclesIds.contains(vehicleId), config.vehicleIdPrefix)
    Console.println(viaEventsWithDuplicates.size + " via events with possible duplicates")

    val viaEvents = removePathDuplicates(viaEventsWithDuplicates)
    Console.println(viaEvents.size + " via events without duplicates")

    Writer.writeViaEvents(viaEvents, config.viaEventsPath)
    Writer.writeViaIdFile(typeToIdSeq, config.viaIdGoupsFilePath)

    if (config.buildTrackPersonScript) {
      val script = createFollowPersonScript(viaEvents, config)
      Writer.writeSeqOfString(script, config.viaFollowPersonScriptPath)
      Console.println("follow person script written into " + config.viaFollowPersonScriptPath)
    }
  }

  def createFollowPersonScript(events: Traversable[ViaEvent], config: RunConfig): Traversable[String] = {
    val networkXml = XML.loadFile(config.networkPath)
    val linksMap = LinkCoordinate.parseNetwork(networkXml)

    def getCoordinates(linkId: Int) = linksMap.get(linkId) match {
      case None =>
        Console.println("Missing link '%d' coordinates from network".format(linkId))
        None

      case some => some
    }

    def getLinkStart(linkId: Int) = getCoordinates(linkId) match {
      case Some(linkCoordinate) => Some(linkCoordinate.from)
      case _                    => None
    }

    def getLinkEnd(linkId: Int) = getCoordinates(linkId) match {
      case Some(linkCoordinate) => Some(linkCoordinate.to)
      case _                    => None
    }

    val script = FollowActorScript.build(events, 2000, 2000, 200, getLinkStart, getLinkEnd)

    script
  }

}
