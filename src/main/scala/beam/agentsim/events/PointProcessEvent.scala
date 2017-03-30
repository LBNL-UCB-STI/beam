package beam.agentsim.events

import java.time.ZonedDateTime
import java.util

import beam.agentsim.routing.opentripplanner.OpenTripPlannerRouter.BeamGraphPath
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.core.api.internal.HasPersonId

import scala.collection.immutable

/**
  * BEAM
  */
class PointProcessEvent (time: Double, id: Id[Person], pointProcessType: String, location: Coord, intensity: Double = 1.0 ) extends Event(time) with HasPersonId {

  import PathTraversalEvent.EVENT_TYPE

  val ATTRIBUTE_VIZ_DATA: String = "viz_data"
  val ATTRIBUTE_LOCATION: String = "location"
  val ATTRIBUTE_INTENSITY: String = "intensity"
  val ATTRIBUTE_POINT_PROCESS_TYPE: String = "type"
  val ATTRIBUTE_AGENT_ID: String = "agent_id"

  override def getEventType: String = EVENT_TYPE

  override def getPersonId: Id[Person] = id

  def createStartBurst(location: Coord, intensity: Double, pointProcessType: String,
                       radialLength: Double = 0.005, paceInTicksPerFrame: Double = 1, numRays: Int = 8,
                       directionOut: Boolean = true) : String = {
    ""
  }

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    val epochSeconds: Long =ZonedDateTime.parse("2016-10-17T00:00:00-07:00[UTC-07:00]").toEpochSecond
    val vizString = createStartBurst(location,intensity,pointProcessType)
    attr.put(ATTRIBUTE_AGENT_ID, id.toString)
    attr.put(ATTRIBUTE_LOCATION, vizString)
    attr.put(ATTRIBUTE_INTENSITY, vizString)
    attr.put(ATTRIBUTE_POINT_PROCESS_TYPE, vizString)
    attr.put(ATTRIBUTE_VIZ_DATA, vizString)
    attr
  }
}

object PointProcessEvent {
  val EVENT_TYPE = "pointProcess"
}

