package beam.agentsim.events

import java.util

import beam.router.opentripplanner.OpenTripPlannerRouter.BeamLeg
import io.circe.syntax._
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId
import beam.agentsim.utils.JsonUtils.syntax._

/**
  * Created by sfeygin on 3/27/17.
  */
case class PathTraversalEvent(id: Id[Person], beamLeg: BeamLeg) extends Event(beamLeg.startTime) with HasPersonId {

  import PathTraversalEvent.EVENT_TYPE

  val ATTRIBUTE_VIZ_DATA: String = "viz_data"
  val ATTRIBUTE_LINK_IDS: String = "links"
  val ATTRIBUTE_MODE: String = "mode"
  val ATTRIBUTE_AGENT_ID: String = "agent_id"

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = id

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_AGENT_ID, id.toString)
    attr.put(ATTRIBUTE_VIZ_DATA, beamLeg.asJson.noSpaces)
    attr.put(ATTRIBUTE_LINK_IDS, beamLeg.graphPath.linkIds.mkString(","))
    attr


  }

}

object PathTraversalEvent {
  val EVENT_TYPE = "pathTraversal"
}

