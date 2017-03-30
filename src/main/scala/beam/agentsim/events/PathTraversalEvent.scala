package beam.agentsim.events

import java.time.ZonedDateTime
import java.util

import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.BeamGraphPath
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId

import scala.collection.immutable

/**
  * Created by sfeygin on 3/27/17.
  */
case class PathTraversalEvent(time: Double, id: Id[Person], beamGraphPath: BeamGraphPath, mode: String) extends Event(time) with HasPersonId {

  import PathTraversalEvent.EVENT_TYPE

  val ATTRIBUTE_VIZ_DATA: String = "viz_data"
  val ATTRIBUTE_LINK_IDS: String = "links"
  val ATTRIBUTE_MODE: String = "mode"
  val ATTRIBUTE_AGENT_ID: String = "agent_id"

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = id

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    val epochSeconds: Long =ZonedDateTime.parse("2016-10-17T00:00:00-07:00[UTC-07:00]").toEpochSecond
    val times: immutable.Seq[Long] = beamGraphPath.entryTimes match {
      case Some(entryTimes) =>
        for{time<-entryTimes} yield time - epochSeconds
      case None =>
        immutable.Seq[Long]()
    }
    val vizString = beamGraphPath.latLons match {
      case Some(latLons) =>
        latLons.map {
          c => s"""\"begin_shape\": [%.6f,%.6f],\"begin_time\":""".format (c.getX, c.getY)
          } zip
          times map {
          x => s"$x"
          } map
          (x => x.replace ("(", "").replace (")", "").replace (":,", ":") ) mkString
          (s"""[{\"travel_type\": "$mode",""", "},{", "}]")
      case None =>
        """[{\"travel_type\": \"ERROR\",\"begin_shape\": [0.0,0.0],\"begin_time\":0}]"""
    }
    attr.put(ATTRIBUTE_AGENT_ID, id.toString)
    attr.put(ATTRIBUTE_VIZ_DATA, vizString)
    attr.put(ATTRIBUTE_LINK_IDS, beamGraphPath.linkIds.mkString(","))
    attr
  }
}

object PathTraversalEvent {
  val EVENT_TYPE = "pathTraversal"
}

