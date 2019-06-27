package beam.utils.beamToVia

import beam.utils.beamToVia.beamEvent.BeamEvent
import org.matsim.api.core.v01.events.Event

import scala.collection.mutable
import scala.xml.XML

object EventsFromPathOfAPerson extends App {
  //val sourcePath = "D:/Work/BEAM/Via-beamville/0.events.xml"

  val sourcePath = "D:/Work/BEAM/Via-fs-light/2.events.xml" // 2.events.csv

  val networkPath = "D:/Work/BEAM/Via-fs-light/physsim-network.xml"
  val outputEventsPath = sourcePath + ".via.events.xml"

  val events = EventsReader
      .fromFile(sourcePath)
      .getOrElse(Seq.empty[BeamEvent])

  Writer.writeSeqOfString(events.map(_.toXml.toString()),sourcePath + ".sorted.xml")

  //"022802-2012001386215-0-6282252", "060700-2013001017578-0-4879259", "021800-2015000742202-0-4510985",
  val interestingPersons = mutable.HashSet("010900-2016000955704-0-6276349")

  def personIsInterested(personId: String): Boolean = {
    //true
    interestingPersons.contains(personId)
  }

  val processedEvents =
    EventsTransformer.filterAndFixEvents(events, personIsInterested)

  val (viaLinkEvents, typeToIdsMap) = EventsTransformer.transform(processedEvents)

  if (interestingPersons.size == 1) {
    // createFollowPersonScript(interestingPersons.head)
  }

  Writer.writeEvents(viaLinkEvents, typeToIdsMap, outputEventsPath)

  // not ready to use
  def createFollowPersonScript(person: String): Unit = {
    val networkXml = XML.loadFile(networkPath)
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

    val script = FollowActorScript.build(viaLinkEvents, getLinkStart, getLinkEnd)
    Writer.writeSeqOfString(script, outputEventsPath + ".follow." + person + ".via.js")
  }
}
