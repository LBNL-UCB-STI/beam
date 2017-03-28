package beam.agentsim.events

import java.util

import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.BeamGraphPath
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId

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
    val viz_data = beamGraphPath.latLons.get map { c => s"'begin_shape':[${c.getX},${c.getY}]" } zip
      beamGraphPath.entryTimes.get map { x => s"'begin_time':$x" } map
      (x => x.replace("(", "").replace(")", "")) mkString
      ("{", "},{", "}")
    attr.put(ATTRIBUTE_AGENT_ID, id.toString)
    attr.put(ATTRIBUTE_VIZ_DATA, viz_data)
    attr.put(ATTRIBUTE_LINK_IDS, beamGraphPath.linkIds.mkString(","))
    attr.put(ATTRIBUTE_MODE, mode)
    attr
  }
}

object PathTraversalEvent {
  val EVENT_TYPE = "pathTraversal"
}
