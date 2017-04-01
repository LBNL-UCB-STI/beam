package beam.agentsim.events

import java.util

import beam.agentsim.events.PointProcessEvent._
import org.geotools.geometry.DirectPosition2D
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.Person
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.api.internal.HasPersonId

import scala.math._

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

  def createStarBurst(time: Double, location: Coord, intensity: Double, pointProcessType: String,
                      radialLength: Double = 350, paceInTicksPerFrame: Double = 25, numRays: Int = 10,
                      directionOut: Boolean = true, numFrames: Int = 4, doTransform: Boolean = false) : String = {
    val radiusFromOrigin : Vector[Double] = (for(i <- 0 to numFrames - 1) yield ( radialLength * i / (numFrames - 1) )).toVector
    val deltaRadian = 2.0 * Pi / numRays
    val frameIndices = if(directionOut){ 0 to numFrames - 1}else{ numFrames - 1 to 0}
    val vizData = for(rayIndex <- 0 to numRays - 1) yield {
      for(frameIndex <- frameIndices)  yield {
        val len = radiusFromOrigin(frameIndex)
        var x = location.getX + len * cos(deltaRadian * rayIndex)
        var y = location.getY + len * sin(deltaRadian * rayIndex)
        if(doTransform){
          val thePos = new DirectPosition2D(x,y)
          val thePosTransformed = new DirectPosition2D(x,y)
          transform.transform(thePos, thePosTransformed)
          x = thePosTransformed.x
          y = thePosTransformed.y
        }
        s"""\"shp\":[%.6f,%.6f],\"tim\":""".format(x, y) + (time + paceInTicksPerFrame*frameIndex) mkString
      }
    }
    val resultStr = ((for(x <- vizData)yield(x.mkString("},{"))).mkString("},{"))
    "[{\"typ\":\"" + pointProcessType + "\",\"val\":"+(s"""%.3f""".format(intensity))+","+resultStr+"}]"
  }

  override def getAttributes: util.Map[String, String] = {
    val attr: util.Map[String, String] = super.getAttributes
    val doTheTransform = location.getX < -400 | location.getX > 400
    val vizString = createStartBurst(time,location,intensity,pointProcessType,doTransform = doTheTransform)
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
  val transform = CRS.findMathTransform(CRS.decode("EPSG:26910", true), CRS.decode("EPSG:4326", true), false)
}

/* For debugging in REPL

*/
