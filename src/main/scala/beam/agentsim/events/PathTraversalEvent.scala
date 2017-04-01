package beam.agentsim.events

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
    val times: immutable.Seq[Long] = beamGraphPath.entryTimes match {
      case Some(entryTimes) =>
        for{time<-entryTimes} yield time
      case None =>
        immutable.Seq[Long]()
    }
    val vizString = beamGraphPath.latLons match {
      case Some(latLons) =>
        latLons.map {
          c => s"""\"shp\":[%.6f,%.6f],\"tim\":""".format (c.getX, c.getY)
          } zip
          times map {
          x => s"$x"
          } map
          (x => x.replace ("(", "").replace (")", "").replace (":,", ":") ) mkString
          (s"""[{\"typ\":"$mode",""", "},{", "}]")
      case None =>
        s"""[{\"typ\":\"ERROR\",\"shp\":[0.0,0.0],\"tim\":0}]"""
    }
    attr.put(ATTRIBUTE_AGENT_ID, id.toString)
    attr.put(ATTRIBUTE_VIZ_DATA, vizString)
    attr.put(ATTRIBUTE_LINK_IDS, beamGraphPath.linkIds.mkString(","))
    attr
  }
}

object PathTraversalEvent {
  val EVENT_TYPE = "pathTraversal"
  val EVENTS_TO_PUBLISH: Vector[String] = Vector[String]("BUS","CAR","WALK","ERROR")
}

