package beam.sim.common

import beam.agentsim.events.SpaceTime
import beam.sim.config.BeamConfig
import beam.utils.ProfilingUtils
import beam.utils.logging.ExponentialLazyLogging
import beam.utils.map.GpxPoint
import com.conveyal.r5.profile.StreetMode
import com.conveyal.r5.streets.{EdgeStore, Split, StreetLayer}
import com.conveyal.r5.transit.TransportNetwork
import com.google.inject.{ImplementedBy, Inject}
import com.vividsolutions.jts.geom.{Coordinate, Envelope}
import org.matsim.api.core.v01
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation

case class EdgeWithCoord(edgeIndex: Int, wgsCoord: Coordinate)

/**
  * Created by sfeygin on 4/2/17.
  */

@ImplementedBy(classOf[GeoUtilsImpl])
trait GeoUtils extends ExponentialLazyLogging {

  def localCRS: String

  lazy val utm2Wgs: GeotoolsTransformation =
    new GeotoolsTransformation(localCRS, "EPSG:4326")
  lazy val wgs2Utm: GeotoolsTransformation =
    new GeotoolsTransformation("EPSG:4326", localCRS)

  def wgs2Utm(spacetime: SpaceTime): SpaceTime = SpaceTime(wgs2Utm(spacetime.loc), spacetime.time)

  def wgs2Utm(coord: Coord): Coord = {
    if (GeoUtils.isInvalidWgsCoordinate(coord)) {
      logger.warn(s"Coordinate does not appear to be in WGS. No conversion will happen: $coord")
      coord
    } else {
      wgs2Utm.transform(coord)
    }
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

  def distUTMInMeters(coord1: Coord, coord2: Coord): Double = GeoUtils.distUTMInMeters(coord1, coord2)

  def distLatLon2Meters(coord1: Coord, coord2: Coord): Double = distUTMInMeters(wgs2Utm(coord1), wgs2Utm(coord2))

  def getNearestR5EdgeToUTMCoord(streetLayer: StreetLayer, coordUTM: Coord, maxRadius: Double = 1E5): Int = {
    getNearestR5Edge(streetLayer, utm2Wgs(coordUTM), maxRadius)
  }

  def getNearestR5Edge(streetLayer: StreetLayer, coordWGS: Coord, maxRadius: Double = 1E5): Int = {
    val theSplit = getR5Split(streetLayer, coordWGS, maxRadius, StreetMode.WALK)
    if (theSplit == null) {
      val closestEdgesToTheCorners = ProfilingUtils
        .timed("getEdgesCloseToBoundingBox", x => logger.info(x)) {
          getEdgesCloseToBoundingBox(streetLayer)
        }
        .map { case (edgeWithCoord, gpxPoint) => edgeWithCoord }
      val closest = closestEdgesToTheCorners.minBy { edge =>
        val matsimUtmCoord = wgs2Utm(new v01.Coord(edge.wgsCoord.x, edge.wgsCoord.y))
        distUTMInMeters(matsimUtmCoord, wgs2Utm(coordWGS))
      }
      val distUTM = distUTMInMeters(wgs2Utm(coordWGS), wgs2Utm(new v01.Coord(closest.wgsCoord.x, closest.wgsCoord.y)))
      logger.warn(
        s"""The split is `null` for StreetLayer.BoundingBox: ${streetLayer.getEnvelope}, coordWGS: $coordWGS, maxRadius: $maxRadius.
           | Will return closest to the corner: $closest which is $distUTM meters far away""".stripMargin
      )
      closest.edgeIndex
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

  def getEdgesCloseToBoundingBox(streetLayer: StreetLayer): Array[(EdgeWithCoord, GpxPoint)] = {
    val cursor = streetLayer.edgeStore.getCursor()
    val iter = new Iterator[EdgeStore#Edge] {
      override def hasNext: Boolean = cursor.advance()

      override def next(): EdgeStore#Edge = cursor
    }

    val boundingBox = streetLayer.envelope

    val insideBoundingBox = iter
      .flatMap { edge =>
        Option(edge.getGeometry.getBoundary.getCoordinate).map { coord =>
          EdgeWithCoord(edge.getEdgeIndex, coord)
        }
      }
      .withFilter(x => boundingBox.contains(x.wgsCoord))
      .toArray

    /*
    min => x0,y0
    max => x1,y1
x0,y1 (TOP LEFT)    ._____._____. x1,y1 (TOP RIGHT)
                    |           |
                    |           |
                    .           .
                    |           |
                    |           |
x0,y0 (BOTTOM LEFT) ._____._____. x1, y0 (BOTTOM RIGHT)
     */

    val bottomLeft = new Coord(boundingBox.getMinX, boundingBox.getMinY)
    val topLeft = new Coord(boundingBox.getMinX, boundingBox.getMaxY)
    val topRight = new Coord(boundingBox.getMaxX, boundingBox.getMaxY)
    val bottomRight = new Coord(boundingBox.getMaxX, boundingBox.getMinY)
    val midLeft = new Coord((bottomLeft.getX + topLeft.getX) / 2, (bottomLeft.getY + topLeft.getY) / 2)
    val midTop = new Coord((topLeft.getX + topRight.getX) / 2, (topLeft.getY + topRight.getY) / 2)
    val midRight = new Coord((topRight.getX + bottomRight.getX) / 2, (topRight.getY + bottomRight.getY) / 2)
    val midBottom = new Coord((bottomLeft.getX + bottomRight.getX) / 2, (bottomLeft.getY + bottomRight.getY) / 2)

    val corners = Array(
      GpxPoint("BottomLeft", bottomLeft),
      GpxPoint("TopLeft", topLeft),
      GpxPoint("TopRight", topRight),
      GpxPoint("BottomRight", bottomRight),
      GpxPoint("MidLeft", midLeft),
      GpxPoint("MidTop", midTop),
      GpxPoint("MidRight", midRight),
      GpxPoint("MidBottom", midBottom)
    )

    val closestEdges = corners.map { gpxPoint =>
      val utmCornerCoord = wgs2Utm(gpxPoint.wgsCoord)
      val closestEdge: EdgeWithCoord = insideBoundingBox.minBy { x =>
        val utmCoord = wgs2Utm(new Coord(x.wgsCoord.x, x.wgsCoord.y))
        distUTMInMeters(utmCornerCoord, utmCoord)
      }
      (closestEdge, gpxPoint)
    }
    closestEdges
  }
}

object GeoUtils {
  import scala.language.implicitConversions

  implicit def toJtsCoordinate(coord: Coord): Coordinate = {
    new Coordinate(coord.getX, coord.getY)
  }

  val GeoUtilsWgs: GeoUtils = new GeoUtils {
    override def localCRS: String = "EPSG:4326"
  }

  val GeoUtilsNad83: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  def fromEpsg(code: String): GeoUtils = {
    code match {
      case "26910" => GeoUtilsNad83
      case "4326"  => GeoUtilsWgs
      case _       => throw new IllegalArgumentException("")
    }
  }

  def isInvalidWgsCoordinate(coord: Coord): Boolean = {
    coord.getX < -180 || coord.getX > 180 || coord.getY < -90 || coord.getY > 90
  }

  def distFormula(coord1: Coord, coord2: Coord): Double = {
    distFormula(coord1.getX, coord1.getY, coord2.getX, coord2.getY)
  }

  def distFormula(x1: Double, y1: Double, x2: Double, y2: Double): Double = {
    Math.sqrt(Math.pow(x1 - x2, 2.0) + Math.pow(y1 - y2, 2.0))
  }

  /**
    * Calculate the Minkowski distance between two coordinates. Provided coordinates need to be in UTM.
    *
    * Source: Shahid, Rizwan, u. a. „Comparison of Distance Measures in Spatial Analytical Modeling for Health Service Planning“. BMC Health Services Research, Bd. 9, Nr. 1, Dezember 2009. Crossref, doi:10.1186/1472-6963-9-200.
    *
    * @param coord1 first coordinate in UTM
    * @param coord2 second coordinate in UTM
    * @return distance in meters
    */
  def minkowskiDistFormula(coord1: Coord, coord2: Coord): Double = {
    val exponent: Double = 3 / 2.toDouble
    val a = Math.pow(Math.abs(coord1.getX - coord2.getX), exponent)
    val b = Math.pow(Math.abs(coord1.getY - coord2.getY), exponent)
    Math.pow(a + b, 1 / exponent)
  }

  sealed trait TurningDirection
  case object Straight extends TurningDirection
  case object SoftLeft extends TurningDirection
  case object Left extends TurningDirection
  case object HardLeft extends TurningDirection
  case object SoftRight extends TurningDirection
  case object Right extends TurningDirection
  case object HardRight extends TurningDirection

  /**
    * Get the desired direction to be taken , based on the angle between the coordinates
    *
    * @param source source coordinates
    * @param destination destination coordinates
    * @return Direction to be taken ( L / SL / HL / R / HR / SR / S)
    */
  def getDirection(source: Coord, destination: Coord): TurningDirection = {
    val radians = computeAngle(source, destination)
    radians match {
      case _ if radians < 0.174533 || radians >= 6.10865 => Straight
      case _ if radians >= 0.174533 & radians < 1.39626  => SoftLeft
      case _ if radians >= 1.39626 & radians < 1.74533   => Left
      case _ if radians >= 1.74533 & radians < 3.14159   => HardLeft
      case _ if radians >= 3.14159 & radians < 4.53785   => HardRight
      case _ if radians >= 4.53785 & radians < 4.88692   => Right
      case _ if radians >= 4.88692 & radians < 6.10865   => SoftRight
      case _                                             => Straight
    }
  }

  /**
    * Generate the vector coordinates from the link nodes
    *
    * @param link link in the network
    * @return vector coordinates
    */
  def vectorFromLink(link: Link): Coord = {
    new Coord(
      link.getToNode.getCoord.getX - link.getFromNode.getCoord.getX,
      link.getToNode.getCoord.getY - link.getFromNode.getCoord.getY
    )
  }

  /**
    * Generate the vector coordinates from the link nodes
    *
    * @param link link in the network
    * @return vector coordinates
    */
  def linkCenter(link: Link): Coord = {
    new Coord(
      (link.getToNode.getCoord.getX + link.getFromNode.getCoord.getX) / 2,
      (link.getToNode.getCoord.getY + link.getFromNode.getCoord.getY) / 2
    )
  }

  /**
    * Computes the angle between two coordinates
    *
    * @param source source coordinates
    * @param destination destination coordinates
    * @return angle between the coordinates (in radians).
    */
  def computeAngle(source: Coord, destination: Coord): Double = {
    val rad = Math.atan2(
      source.getX * destination.getY - source.getY * destination.getX,
      source.getX * destination.getX - source.getY * destination.getY
    )
    if (rad < 0) {
      rad + 3.141593 * 2.0
    } else {
      rad
    }
  }

  def distUTMInMeters(coord1: Coord, coord2: Coord): Double = {
    Math.sqrt(Math.pow(coord1.getX - coord2.getX, 2.0) + Math.pow(coord1.getY - coord2.getY, 2.0))
  }

  def getR5EdgeCoord(linkIdInt: Int, transportNetwork: TransportNetwork): Coord = {
    val currentEdge = transportNetwork.streetLayer.edgeStore.getCursor(linkIdInt)
    new Coord(currentEdge.getGeometry.getCoordinate.x, currentEdge.getGeometry.getCoordinate.y)
  }

}

class GeoUtilsImpl @Inject()(val beamConfig: BeamConfig) extends GeoUtils {
  override def localCRS: String = beamConfig.beam.spatial.localCRS
}

case class SimpleGeoUtils(localCRS: String = "epsg:26910") extends GeoUtils
