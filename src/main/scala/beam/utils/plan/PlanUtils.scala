package beam.utils.plan

import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Population => MATSimPopulation}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._

object PlanUtils {
  private lazy val log = LoggerFactory.getLogger(this.getClass)

  def buildActivityQuadTreeBounds(population: MATSimPopulation, beamConfig: BeamConfig): QuadTreeBounds = {
    val persons = population.getPersons.values().asInstanceOf[java.util.Collection[Person]].asScala.view
    val activities = persons.flatMap(p => p.getSelectedPlan.getPlanElements.asScala.view).collect {
      case activity: Activity =>
        activity
    }
    val coordinates = activities.map(_.getCoord)
    // Force to compute xs and ys arrays
    val xs = coordinates.map(_.getX).toArray
    val ys = coordinates.map(_.getY).toArray
    val xMin = xs.min
    val xMax = xs.max
    val yMin = ys.min
    val yMax = ys.max
    log.info(
      s"QuadTreeBounds with X: [$xMin; $xMax], Y: [$yMin, $yMax]. boundingBoxBuffer: ${beamConfig.beam.spatial.boundingBoxBuffer}"
    )
    QuadTreeBounds(
      xMin - beamConfig.beam.spatial.boundingBoxBuffer,
      yMin - beamConfig.beam.spatial.boundingBoxBuffer,
      xMax + beamConfig.beam.spatial.boundingBoxBuffer,
      yMax + beamConfig.beam.spatial.boundingBoxBuffer
    )
  }

}
