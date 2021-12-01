package beam.agentsim.infrastructure.taz

import scala.annotation.tailrec
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._

/**
  * represents a Traffic Analysis Zone
  * @param tazId unique identifier of this TAZ
  * @param coord location of the centroid of this TAZ
  * @param areaInSquareMeters area of TAZ
  */
class TAZ(val tazId: Id[TAZ], val coord: Coord, val areaInSquareMeters: Double) {
  def this(tazIdString: String, coord: Coord, area: Double) {
    this(Id.create(tazIdString, classOf[TAZ]), coord, area)
  }
}

object TAZ {

  val DefaultTAZId: Id[TAZ] = Id.create("default", classOf[TAZ])
  val EmergencyTAZId: Id[TAZ] = Id.create("emergency", classOf[TAZ])

  val DefaultTAZ: TAZ = new TAZ(DefaultTAZId, new Coord(), 0)

  /**
    * performs a concentric disc search from the present location to find TAZs up to the SearchMaxRadius
    * @param tazQuadTree tree to search
    * @param searchCenter central location from which concentric discs will be built with an expanding radius
    * @param startRadius the beginning search radius
    * @param maxRadius search constrained to this maximum search radius
    * @return the TAZs found in the first search disc which locates a TAZ center, along with their distances, not sorted
    */
  def discSearch(
    tazQuadTree: QuadTree[TAZ],
    searchCenter: Location,
    startRadius: Double,
    maxRadius: Double
  ): List[(TAZ, Double)] = {

    @tailrec
    def _find(thisRadius: Double): List[TAZ] = {
      if (thisRadius > maxRadius) List.empty[TAZ]
      else {
        val found = tazQuadTree
          .getDisk(searchCenter.getX, searchCenter.getY, thisRadius)
          .asScala
          .toList
        if (found.nonEmpty) found
        else _find(thisRadius * 2)
      }
    }

    _find(startRadius).map { taz =>
      // Note, this assumes both TAZs and SearchCenter are in local coordinates, and therefore in units of meters
      (taz, GeoUtils.distFormula(taz.coord, searchCenter))
    }
  }

  /**
    * performs a concentric ring search from the present location to find TAZs up to the SearchMaxRadius
    * @param tazQuadTree tree to search
    * @param searchCenter central location from which concentric discs will be built with an expanding radius
    * @param startRadius the beginning search radius
    * @param maxRadius search constrained to this maximum search radius
    * @return the TAZs found in the first search ring which locates a TAZ center, along with their distances, not sorted
    */
  def ringSearch(
    tazQuadTree: QuadTree[TAZ],
    searchCenter: Location,
    startRadius: Double,
    maxRadius: Double
  ): List[(TAZ, Double)] = {

    @tailrec
    def _find(innerRadius: Double, outerRadius: Double): List[TAZ] = {
      if (innerRadius > maxRadius) List.empty[TAZ]
      else {
        val found = tazQuadTree
          .getRing(searchCenter.getX, searchCenter.getY, innerRadius, outerRadius)
          .asScala
          .toList
        if (found.nonEmpty) found
        else _find(outerRadius, outerRadius * 2)
      }
    }

    _find(0.0, startRadius).map { taz =>
      // Note, this assumes both TAZs and SearchCenter are in local coordinates, and therefore in units of meters
      (taz, GeoUtils.distFormula(taz.coord, searchCenter))
    }
  }
}
