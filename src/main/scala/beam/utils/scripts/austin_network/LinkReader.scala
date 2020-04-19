package beam.utils.scripts.austin_network

import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.network.Link
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.gis.{PointFeatureFactory, ShapeFileWriter}
import org.opengis.feature.simple.SimpleFeature
import scala.collection.JavaConverters._

import scala.collection.mutable.ArrayBuffer
import scala.io.Source
case class LinkDetails(
  linkChainId: Id[Link],
  length: Double,
  speedInMetersPerSecond: Double,
  capacity: Int,
  lanes: Int,
  geometry: List[Coord]
)

object LinkReader {

  val skipLine = 6 // 5 for information and 1 for header
  def main(args: Array[String]): Unit = {
    val detailFilePath = "E:\\work\\austin\\austin.2015_regional_am.public.linkdetails.csv"
    val publicLinkPath = "E:\\work\\austin\\austin.2015_regional_am.public.links.csv"
    val linkDetails: Vector[LinkDetails] = getLinkDataWithCapacities(detailFilePath, publicLinkPath)
    createShapeFile(linkDetails,"E:\\work\\austin\\capacityAustin.shp")

    //linkDetails.foreach(linkDetails => println(linkDetails))

  }

  def getLinkDataWithCapacities(detailFilePath: String, publicLinkPath: String) = {
    val publicLinkSource = Source.fromFile(publicLinkPath)
    val publicLink = publicLinkSource.getLines().toVector.drop(skipLine).map(linkGeometry).toMap
    publicLinkSource.close()

    val detailLinkSource = Source.fromFile(detailFilePath)
    val linkDetails = detailLinkSource.getLines().toVector.drop(skipLine).map(linkDetail(publicLink))
    detailLinkSource.close()
    linkDetails.filterNot(linkDetails => linkDetails.capacity==100000)
  }

  def createShapeFile(linkDetails: Vector[LinkDetails], shapeFileOutputPath:String)={
    val features = ArrayBuffer[SimpleFeature]()

    val pointf: PointFeatureFactory = new PointFeatureFactory.Builder()
      .setCrs(MGC.getCRS("EPSG:4326"))
      .setName("nodes")
      .addAttribute("Resolution", classOf[java.lang.Integer])
      .addAttribute("capacity", classOf[java.lang.Integer])
      .addAttribute("lanes", classOf[java.lang.Integer])
      .addAttribute("speedInMPS", classOf[java.lang.Double])
      .create()

    linkDetails.foreach{ link =>
      link.geometry.foreach{ wsgCoord=>
        val coord=new com.vividsolutions.jts.geom.Coordinate(wsgCoord.getX, wsgCoord.getY)
        val feature=pointf.createPoint(coord)
        feature.setAttribute("capacity",link.capacity)
        feature.setAttribute("lanes",link.lanes)
        feature.setAttribute("speedInMPS",link.speedInMetersPerSecond)
        features+=feature
      }
    }

    ShapeFileWriter.writeGeometries(features.asJava,shapeFileOutputPath)
  }



  def linkDetail(linkGeometry: Map[Id[Link], List[Coord]])(row: String): LinkDetails = {
    val tokens = row.replaceAll("\"", "").split(",")
    val linkId = Id.createLinkId(tokens(0))
    LinkDetails(
      linkId,
      tokens(4).toDouble,
      tokens(5).toDouble*60*0.44704,
      tokens(6).toDouble.toInt,
      tokens(7).toDouble.toInt,
      linkGeometry(linkId)
    )
  }

  def linkGeometry(row: String): (Id[Link], List[Coord]) = {
    val list = row
      .replaceAll("\"", "")
      .replace("[", "")
      .replace("]", "")
      .split(",", 2)
    (Id.createLinkId(list(0)), value(list(1)))
  }

  def value(str: String): List[Coord] = {
    val coordString = str.split("\\),\\(")

    coordString
      .map(
        coord =>
          coord
            .replaceAll("\\(", "")
            .replaceAll("\\)", "")
            .split(",")
      )
      .map(c => new Coord(c(0).toDouble, c(1).toDouble))
      .toList
  }
}
