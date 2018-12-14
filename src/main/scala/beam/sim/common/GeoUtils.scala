package beam.sim.common

import beam.agentsim.events.SpaceTime
import beam.sim.config.BeamConfig
import beam.sim.{BeamServices, HasServices}
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.streets.{Split, StreetLayer}
import com.google.inject.{ImplementedBy, Inject}
import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation

/**
  * Created by sfeygin on 4/2/17.
  */

@ImplementedBy(classOf[GeoUtilsImpl])
trait GeoUtils extends HasServices {
  lazy val utm2Wgs: GeotoolsTransformation =
    new GeotoolsTransformation(beamServices.beamConfig.beam.spatial.localCRS, "EPSG:4326")
  lazy val wgs2Utm: GeotoolsTransformation =
    new GeotoolsTransformation("EPSG:4326", beamServices.beamConfig.beam.spatial.localCRS)

  def wgs2Utm(spacetime: SpaceTime): SpaceTime = SpaceTime(wgs2Utm(spacetime.loc), spacetime.time)

  def wgs2Utm(coord: Coord): Coord = {
    wgs2Utm.transform(coord)
  }

  def wgs2Utm(envelope: Envelope): Envelope = {
    val ll: Coord = wgs2Utm.transform(new Coord(envelope.getMinX, envelope.getMinY))
    val ur: Coord = wgs2Utm.transform(new Coord(envelope.getMaxX, envelope.getMaxY))
    new Envelope(ll.getX, ur.getX, ll.getY, ur.getY)
  }
  def utm2Wgs(spacetime: SpaceTime): SpaceTime = SpaceTime(utm2Wgs(spacetime.loc), spacetime.time)

  def utm2Wgs(coord: Coord): Coord = {
    utm2Wgs.transform(coord)
  }

  def distUTMInMeters(coord1: Coord, coord2: Coord): Double = {
    Math.sqrt(Math.pow(coord1.getX - coord2.getX,2.0) + Math.pow(coord1.getY - coord2.getY,2.0))
  }

  def distLatLon2Meters(coord1: Coord, coord2: Coord): Double =
    distLatLon2Meters(coord1.getX, coord1.getY, coord2.getX, coord2.getY)

  def distLatLon2Meters(x1: Double, y1: Double, x2: Double, y2: Double): Double =
    GeoUtils.distLatLon2Meters(x1, y1, x2, y2)

  def getNearestR5Edge(streetLayer: StreetLayer, coord: Coord, maxRadius: Double = 1E5): Int = {
    val theSplit = getR5Split(streetLayer, coord, maxRadius, StreetMode.WALK)
    if (theSplit == null) {
      Int.MinValue
    } else {
      theSplit.edge
    }
  }

  def coordOfR5Edge(streetLayer: StreetLayer, edgeId: Int): Coord = {
    val theEdge = streetLayer.edgeStore.getCursor(edgeId)
    new Coord(theEdge.getGeometry.getCoordinate.x, theEdge.getGeometry.getCoordinate.y)
  }

  def snapToR5Edge(
                    streetLayer: StreetLayer,
                    coordWGS: Coord,
                    maxRadius: Double = 1E5,
                    streetMode: StreetMode = StreetMode.WALK
  ): Coord = {
    val theSplit = getR5Split(streetLayer, coordWGS, maxRadius, streetMode)
    if (theSplit == null) {
      coordWGS
    } else {
      new Coord(theSplit.fixedLon.toDouble / 1.0E7, theSplit.fixedLat.toDouble / 1.0E7)
    }
  }

  def getR5Split(
    streetLayer: StreetLayer,
    coord: Coord,
    maxRadius: Double = 1E5,
    streetMode: StreetMode = StreetMode.WALK
  ): Split = {
    var radius = 10.0
    var theSplit: Split = null
    while (theSplit == null && radius <= maxRadius) {
      theSplit = streetLayer.findSplit(coord.getY, coord.getX, radius, streetMode)
      radius = radius * 10
    }
    if (theSplit == null) {
      theSplit = streetLayer.findSplit(coord.getY, coord.getX, maxRadius, streetMode)
    }
    theSplit
  }
}

object GeoUtils {

  @Inject
  var beamConfig: BeamConfig = _

  implicit class CoordOps(val coord: Coord) extends AnyVal {

    def toWgs: Coord = {
      lazy val utm2Wgs: GeotoolsTransformation =
        new GeotoolsTransformation(beamConfig.beam.spatial.localCRS, "epsg:4326")
      //TODO fix this monstrosity
      if (coord.getX > 1.0 | coord.getX < -0.0) {
        utm2Wgs.transform(coord)
      } else {
        coord
      }
    }

    def toUtm: Coord = {
      lazy val wgs2Utm: GeotoolsTransformation =
        new GeotoolsTransformation("epsg:4326", beamConfig.beam.spatial.localCRS)
      wgs2Utm.transform(coord)
    }
  }

  def distFormula(coord1: Coord, coord2: Coord): Double = {
    Math.sqrt(Math.pow(coord1.getX - coord2.getX, 2.0) + Math.pow(coord1.getY - coord2.getY, 2.0))
  }

  def distLatLon2Meters(x1: Double, y1: Double, x2: Double, y2: Double): Double = {
    //    http://stackoverflow.com/questions/837872/calculate-distance-in-meters-when-you-know-longitude-and-latitude-in-java
    val earthRadius = 6371000
    val distX = Math.toRadians(x2 - x1)
    val distY = Math.toRadians(y2 - y1)
    val a = Math.sin(distX / 2) * Math.sin(distX / 2) + Math.cos(Math.toRadians(x1)) * Math.cos(
      Math.toRadians(x2)
    ) * Math.sin(distY / 2) * Math.sin(distY / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    val dist = earthRadius * c
    dist

  }
}

class GeoUtilsImpl @Inject()(override val beamServices: BeamServices) extends GeoUtils {}
