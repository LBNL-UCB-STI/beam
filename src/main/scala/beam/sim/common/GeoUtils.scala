package beam.sim.common

import beam.sim.config.{BeamConfig, ConfigModule}
import beam.sim.{BeamServices, BoundingBox, HasServices}
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
trait GeoUtils extends HasServices  {
  lazy val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation(beamServices.beamConfig.beam.spatial.localCRS, "EPSG:4326")
  lazy val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation("EPSG:4326",beamServices.beamConfig.beam.spatial.localCRS)
  lazy val utmbbox: BoundingBox = new BoundingBox(beamServices.beamConfig.beam.spatial.localCRS)

  def wgs2Utm(coord: Coord): Coord = {
    wgs2Utm.transform(coord)
  }
  def wgs2Utm(envelope: Envelope): Envelope = {
    val ll: Coord = wgs2Utm.transform(new Coord(envelope.getMinX, envelope.getMinY))
    val ur: Coord = wgs2Utm.transform(new Coord(envelope.getMaxX, envelope.getMaxY))
    new Envelope(ll.getX, ur.getX, ll.getY, ur.getY)
  }
  def utm2Wgs(coord:Coord): Coord = {
    //TODO fix this monstrosity
    if (coord.getX > 1.0 | coord.getX < -0.0) {
      utm2Wgs.transform(coord)
    } else {
      coord
    }
  }
  //TODO this is a hack, but we need a general purpose, failsafe way to get distances out of Coords regardless of their projection
  def distInMeters(coord1: Coord, coord2: Coord): Double = {
    distLatLon2Meters(utm2Wgs(coord1), utm2Wgs(coord2))
  }
  def distLatLon2Meters(coord1: Coord, coord2: Coord): Double = distLatLon2Meters(coord1.getX, coord1.getY, coord2.getX, coord2.getY)

  def distLatLon2Meters(x1: Double, y1: Double, x2: Double, y2: Double): Double = {
    //    http://stackoverflow.com/questions/837872/calculate-distance-in-meters-when-you-know-longitude-and-latitude-in-java
    val earthRadius = 6371000
    val distX = Math.toRadians(x2 - x1)
    val distY = Math.toRadians(y2 - y1)
    val a = Math.sin(distX / 2) * Math.sin(distX / 2) + Math.cos(Math.toRadians(x1)) * Math.cos(Math.toRadians(x2)) * Math.sin(distY / 2) * Math.sin(distY / 2)
    val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
    val dist = earthRadius * c
    dist

  }

  //TODO if we want to dynamically determined BBox extents, this should be updated to be generic to CRS of the BBox
  def observeCoord(coord: Coord, boundingBox: BoundingBox): Unit = {
//    val posTransformed = if (boundingBox.crs == "EPSG:4326" && coord.getX <= 180.0 & coord.getX >= -180.0 & coord.getY > -90.0 & coord.getY < 90.0) {
//      wgs2utm.transform(coord)
//    }else{
//      coord
//    }
//    if (posTransformed.getX < boundingBox.minX) boundingBox.minX = posTransformed.getX
//    if (posTransformed.getY < boundingBox.minY) boundingBox.minY = posTransformed.getY
//    if (posTransformed.getX > boundingBox.maxX) boundingBox.maxX = posTransformed.getX
//    if (posTransformed.getY > boundingBox.maxY) boundingBox.maxY = posTransformed.getY
  }

  def snapToR5Edge(streetLayer: StreetLayer, coord: Coord, maxRadius: Double = 1E5, streetMode: StreetMode = StreetMode.WALK): Coord = {
    var radius = 100.0
    var theSplit: Split = null
    while(theSplit == null && radius < maxRadius) {
      theSplit = streetLayer.findSplit(coord.getY, coord.getX, 1000, streetMode);
      radius = radius * 10
    }
    new Coord(theSplit.fixedLon.toDouble / 1.0E7, theSplit.fixedLat.toDouble / 1.0E7)
  }
}

object GeoUtils {

  implicit class CoordOps(val coord: Coord) extends AnyVal{

    def toWgs: Coord= {
      lazy val config = BeamConfig(ConfigModule.typesafeConfig)
      lazy val utm2Wgs: GeotoolsTransformation = new GeotoolsTransformation(config.beam.spatial.localCRS, "epsg:4326")
      //TODO fix this monstrosity
      if (coord.getX > 1.0 | coord.getX < -0.0) {
        utm2Wgs.transform(coord)
      } else {
        coord
      }
    }

    def toUtm: Coord ={
      lazy val config = BeamConfig(ConfigModule.typesafeConfig)
      lazy val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation("epsg:4326",config.beam.spatial.localCRS)
      wgs2Utm.transform(coord)
    }
  }
}

class GeoUtilsImpl @Inject()(override val beamServices: BeamServices) extends GeoUtils{}

