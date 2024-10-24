package beam.utils.csv.writers

import org.matsim.api.core.v01.Scenario

import scala.collection.JavaConverters._
import ScenarioCsvWriter._
import beam.utils.{OutputDataDescriptor, OutputDataDescriptorObject}

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
          attributeOrigId = Option(link.getAttributes.getAttribute("origid")).map(_.toString).getOrElse(""),
          attributeOrigType = Option(link.getAttributes.getAttribute("type")).map(_.toString).getOrElse(""),
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

  def outputDataDescriptor: OutputDataDescriptor =
    OutputDataDescriptorObject("NetworkCsvWriter", "network.csv.gz")(
      """
        linkId | Id of links that are in the scenario road network
        linkLength | link length
        linkFreeSpeed | link free speed (m/s)
        linkCapacity | link capacity (cars/hour)
        numberOfLanes | number of lanes
        linkModes | List of modes that are allowed on the link separated with ;
        attributeOrigId | Source (OSM) link id
        attributeOrigType | Source (OSM) link type
        fromNodeId | Id of the node link starts on
        toNodeId | Id of the node link ends on
        fromLocationX | X part of link start location coordinate
        fromLocationY | Y part of link start location coordinate
        toLocationX | X part of link end location coordinate
        toLocationY | Y part of link end location coordinate
        """
    )

}
