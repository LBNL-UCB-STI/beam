package beam.agentsim.events

import java.util

import beam.router.RoutingModel.{BeamLeg, BeamStreetPath}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.internal.HasPersonId
import org.matsim.vehicles.Vehicle

/**
  * Created by sfeygin on 3/27/17.
  */
case class PathTraversalEvent(id: Id[Vehicle], beamLeg: BeamLeg) extends Event(beamLeg.startTime) {

  import PathTraversalEvent.EVENT_TYPE

  val ATTRIBUTE_VIZ_DATA: String = "viz_data"
  val ATTRIBUTE_LINK_IDS: String = "links"
  val ATTRIBUTE_MODE: String = "mode"
  val ATTRIBUTE_DEPARTURE_TIME: String = "departure_time"
  val ATTRIBUTE_VEHICLE_ID: String = "vehicle_id"

  override def getEventType: String = EVENT_TYPE

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    attr.put(ATTRIBUTE_VEHICLE_ID, id.toString)
    attr.put(ATTRIBUTE_DEPARTURE_TIME, beamLeg.startTime.toString)
    attr.put(ATTRIBUTE_MODE, beamLeg.mode.toString)
//    attr.put(ATTRIBUTE_VIZ_DATA, beamLeg.asJson.noSpaces)
//    beamLeg.travelPath.swap.foreach(sp => attr.put(ATTRIBUTE_LINK_IDS, sp.linkIds.mkString(",")))
    attr.put(ATTRIBUTE_LINK_IDS, beamLeg.travelPath.asInstanceOf[BeamStreetPath].linkIds.mkString(","))
    attr
  }
}

object PathTraversalEvent {
  val EVENT_TYPE = "PathTraversal"
}

