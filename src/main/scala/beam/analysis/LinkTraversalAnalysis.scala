package beam.analysis

import java.util

import beam.agentsim.events.PathTraversalEvent
import beam.sim.OutputDataDescription
import beam.utils.OutputDataDescriptor
import javax.inject.Inject
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.JavaConverters._
import scala.collection.mutable

class LinkTraversalAnalysis @Inject()
(private val scenario: Scenario) extends BasicEventHandler with OutputDataDescriptor {

  override def handleEvent(event: Event): Unit = {
    event match {
      case pathTraversalEvent: PathTraversalEvent =>
        val eventAttributes: mutable.Map[String, String] = pathTraversalEvent.getAttributes.asScala
        val linkIds = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_LINK_IDS, "").split(",")
        val linkTravelTimes = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_LINK_TRAVEL_TIMES, "").split(",")
        val vehicleId = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_VEHICLE_ID, "")
        val vehicleType = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_VEHICLE_TYPE, "")
        val arrivalTime = eventAttributes.getOrElse(PathTraversalEvent.ATTRIBUTE_ARRIVAL_TIME, "")
        val networkLinks = scenario.getNetwork.getLinks.values().asScala
        val nextLinkIds = linkIds.toList.takeRight(linkIds.size-1)
        linkIds zip nextLinkIds zip linkTravelTimes foreach { tuple =>
          val ((id,nextId),travelTime) = tuple
          val nextLink = networkLinks.find(x => x.getId.toString.equals(nextId))
          networkLinks.find(x => x.getId.toString.equals(id)) match {
            case Some(currentLink) =>
              val linkType = currentLink.getAttributes.getAttribute("type").asInstanceOf[String]
              val freeFlowSpeed = currentLink.getFreespeed
              val averageSpeed = currentLink.getLength / travelTime.toInt
              val (currentX , currentY) = (currentLink.getCoord.getX , currentLink.getCoord.getY)
              val (nextX , nextY) = (nextLink.map(_.getCoord.getX).getOrElse(0.0) ,nextLink.map(_.getCoord.getY).getOrElse(0.0))
              //TODO calculate the angle between the current and next coordinates
            case None =>
          }
        }
      case _ =>
    }
  }

  /**
    * Get description of fields written to the output files.
    *
    * @return list of data description objects
    */
  override def getOutputDataDescriptions: util.List[OutputDataDescription] = List.empty[OutputDataDescription].asJava
}
