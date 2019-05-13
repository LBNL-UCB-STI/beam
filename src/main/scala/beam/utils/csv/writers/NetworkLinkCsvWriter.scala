package beam.utils.csv.writers

import org.matsim.api.core.v01.Scenario

import scala.collection.JavaConverters._

object NetworkLinkCsvWriter extends ScenarioCsvWriter {

  private case class LinkEntry(
    linkId: String,
    linkFrom: String,
    linkTo: String,
    linkLength: String,
    linkFreeSpeed: String,
    linkCapacity: Double,
    numberOfLanes: String,
    linkModes: String,
    attributeOrigId: String,
    attributeOrigType: String
  ) {
    override def toString: String = {
      Seq(
        linkId,
        linkFrom,
        linkTo,
        linkLength,
        linkFreeSpeed,
        linkCapacity,
        numberOfLanes,
        linkModes,
        attributeOrigId,
        attributeOrigType
      ).mkString("", FieldSeparator, LineSeparator)
    }
  }

  override protected val fields: Seq[String] = {
    Seq(
      "linkId",
      "linkFrom",
      "linkTo",
      "linkLength",
      "linkFreeSpeed",
      "linkCapacity",
      "numberOfLanes",
      "linkModes",
      "attributeOrigId",
      "attributeOrigType"
    )
  }

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    scenario.getNetwork.getLinks
      .values()
      .asScala
      .toIterator
      .map { link =>
        val linkModeAsString =
          link.getAllowedModes.asScala
            .mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
        LinkEntry(
          linkId = link.getId.toString,
          linkFrom = link.getFromNode.getId.toString,
          linkTo = link.getToNode.getId.toString,
          linkLength = link.getLength.toString,
          linkFreeSpeed = link.getFreespeed.toString,
          linkCapacity = link.getCapacity(),
          numberOfLanes = link.getNumberOfLanes.toString, // A
          linkModes = linkModeAsString,
          attributeOrigId = String.valueOf(link.getAttributes.getAttribute("origid")),
          attributeOrigType = String.valueOf(link.getAttributes.getAttribute("type")),
        ).toString
      }
  }

}
