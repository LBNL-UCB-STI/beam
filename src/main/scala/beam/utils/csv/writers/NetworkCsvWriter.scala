package beam.utils.csv.writers

import org.matsim.api.core.v01.Scenario

import scala.collection.JavaConverters._

object NetworkCsvWriter extends ScenarioCsvWriter {

  private case class LinkMergeEntry(
    linkId: String,
    linkLength: String,
    linkFreeSpeed: String,
    linkCapacity: Double,
    numberOfLanes: String,
    linkModes: String,
    attributeOrigId: String,
    attributeOrigType: String,
    fromNodeId: String,
    toNodeId: String,
    fromLocationX: String,
    fromLocationY: String,
    toLocationX: String,
    toLocationY: String
  ) {
    override def toString: String = {
      Seq(
        linkId,
        linkLength,
        linkFreeSpeed,
        linkCapacity,
        numberOfLanes,
        linkModes,
        attributeOrigId,
        attributeOrigType,
        fromNodeId,
        toNodeId,
        fromLocationX,
        fromLocationY,
        toLocationX,
        toLocationY
      ).mkString("", FieldSeparator, LineSeparator)
    }
  }

  override protected def fields: Seq[String] = Seq(
    "linkId",
    "linkLength",
    "linkFreeSpeed",
    "linkCapacity",
    "numberOfLanes",
    "linkModes",
    "attributeOrigId",
    "attributeOrigType",
    "fromNodeId",
    "toNodeId",
    "fromLocationX",
    "fromLocationY",
    "toLocationX",
    "toLocationY"
  )

  override def contentIterator(scenario: Scenario): Iterator[String] = {
    scenario.getNetwork.getLinks
      .values()
      .asScala
      .toIterator
      .map { link =>
        val linkModeAsString =
          link.getAllowedModes.asScala
            .mkString(ArrayStartString, ArrayItemSeparator, ArrayEndString)
        LinkMergeEntry(
          linkId = link.getId.toString,
          linkLength = link.getLength.toString,
          linkFreeSpeed = link.getFreespeed.toString,
          linkCapacity = link.getCapacity(),
          numberOfLanes = link.getNumberOfLanes.toString,
          linkModes = linkModeAsString,
          attributeOrigId = String.valueOf(link.getAttributes.getAttribute("origid")),
          attributeOrigType = String.valueOf(link.getAttributes.getAttribute("type")),
          fromNodeId = link.getFromNode.getId.toString,
          toNodeId = link.getToNode.getId.toString,
          fromLocationX = link.getFromNode.getCoord.getX.toString,
          fromLocationY = link.getFromNode.getCoord.getY.toString,
          toLocationX = link.getToNode.getCoord.getX.toString,
          toLocationY = link.getToNode.getCoord.getY.toString
        ).toString
      }
  }

  override def contentIterator[A](elements: Iterator[A]): Iterator[String] = ???

}
