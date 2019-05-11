package beam.utils.csv.writers

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import collection.JavaConverters._

object NetworkLinkCsvWriter extends ScenarioCsvWriter with StrictLogging {

  private case class LinkEntry(
                                linkId: String, linkFrom: String, linkTo: String,
                                linkLength: String, linkFreespeed: String, linkCapacity: Double,
                                linkPermLanes: String, linkOneWay: String, linkModes: String,
                                attributeOrigId: String, attributeOrigType: String
                              ) {
    override def toString: String = {
      Seq(linkId, linkFrom, linkTo,
        linkLength, linkFreespeed, linkCapacity,
        linkPermLanes, linkOneWay, linkModes,
        attributeOrigId, attributeOrigType
      ).mkString("", FieldSeparator, LineSeparator)
    }
  }

  override protected val fields: Seq[String] = {
    Seq("linkId", "linkFrom", "linkTo", "linkLength", "linkFreeSpeed", "linkCapacity",
      "linkPermLanes", "linkOneWay", "linkModes", "attributeOrigId", "attributeOrigType")
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    // TODO: Fix the fields below:
    //    A: is permLanes the same as numberOfLanes?
    //    B, C, D: where this information can be found?
    scenario.getNetwork.getLinks.values().asScala
      .toIterator
      .map { n =>
        val linkModeAsString =
          n.getAllowedModes.asScala
            .filterNot(_ == "null") // TODO: why null is added to the set?
            .mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
        LinkEntry(
          linkId = n.getId.toString,
          linkFrom = n.getFromNode.getId.toString,
          linkTo = n.getToNode.getId.toString,
          linkLength = n.getLength.toString,
          linkFreespeed = n.getFreespeed.toString,
          linkCapacity = n.getCapacity(),
          linkPermLanes = n.getNumberOfLanes.toString, // A
          linkOneWay = "", // B
          linkModes = linkModeAsString,
          attributeOrigId = "", // C
          attributeOrigType = "" // D
        ).toString
      }
  }

}
