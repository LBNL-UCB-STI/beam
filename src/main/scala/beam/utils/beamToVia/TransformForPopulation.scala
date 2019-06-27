package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.{BeamEvent, BeamPersonEntersVehicle, BeamPersonLeavesVehicle}

import scala.collection.mutable
import scala.xml.XML

object TransformForPopulation {

  def transformAndWrite(config: RunConfig): Unit = {

    val events = EventsReader
      .fromFile(config.beamEventsPath)
      .getOrElse(Seq.empty[BeamEvent])

    // Writer.writeSeqOfString(events.map(_.toXml.toString()),sourcePath + ".sorted.xml")

    val selectedPopulation = collectPopulationIds(events, config.populationSampling)

    val processedEvents = EventsTransformer.filterAndFixEvents(events, selectedPopulation.contains)
    val (pathLinkEvents, typeToIdSeq) = EventsTransformer.transform(processedEvents)

    Writer.writeViaEvents(pathLinkEvents, config.viaEventsPath)
    Writer.writeViaIdFile(typeToIdSeq, config.viaIdGoupsFilePath)
  }

  def collectPopulationIds(events: Traversable[BeamEvent], sampling: Seq[PopulationSample]): mutable.HashSet[String] = {
    val population = events.foldLeft(mutable.HashSet.empty[String])((ids, event) => {
      event match {
        case pev: BeamPersonEntersVehicle => ids += pev.personId
        case plv: BeamPersonLeavesVehicle => ids += plv.personId
        case _                            => ids
      }
    })

    population.foldLeft(mutable.HashSet.empty[String])((selected, id) => {
      sampling.foreach(
        rule =>
          if (rule.personIsInteresting(id) && (rule.percentage >= 1.0 || Math.random() >= rule.percentage))
            selected += id
      )

      selected
    })
  }

  /*
def createFollowPersonScript(events:Traversable[BeamEvent], config:RunConfig): Unit = {
    val networkXml = XML.loadFile(config.networkPath)
    val linksMap = LinkCoordinate.parseNetwork(networkXml, Console.println)

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

    val script = FollowActorScript.build(beamEvent, getLinkStart, getLinkEnd)
    Writer.writeSeqOfString(script, outputEventsPath + ".follow." + person + ".via.js")
  }
 */

}
