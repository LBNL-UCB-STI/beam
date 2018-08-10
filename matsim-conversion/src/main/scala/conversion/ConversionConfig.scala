package conversion

import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation

case class ConversionConfig( outputDirectory: String, localCRS: String, matsimNetworkFile: String, shapeConfig: Option[ShapeConfig] = None )

case class ShapeConfig(shapeFile: String, tazIDFieldName: String)

case class OSMFilteringConfig(pbfFile: String, boundingBox: BoundingBoxConfig, outputFile: String)

case class BoundingBoxConfig(top: Double, left: Double, bottom: Double, right: Double)

object ConversionConfig {

  def apply(c: com.typesafe.config.Config): ConversionConfig = {
    val matsimConversionConfig = c.getConfig("matsim.conversion")
    val output = matsimConversionConfig.getString("output")
    val matsimNetworkFile = matsimConversionConfig.getString("matsimNetworkFile")

    val spatialConfig = c.getConfig("beam.spatial")

    //TODO try to detect coordinate system from MATSim network and population file
    val localCRS = spatialConfig.getString("localCRS")

    val mShapeConfig = if(matsimConversionConfig.hasPathOrNull("shapeConfig")){
      val shapeConfig = matsimConversionConfig.getConfig("shapeConfig")
      val shapeFile = shapeConfig.getString("shapeFile")
      val tazIdField = shapeConfig.getString("tazIdFieldName")
      Some(ShapeConfig(shapeFile, tazIdField))
    } else
      None
    ConversionConfig(output, localCRS, matsimNetworkFile, mShapeConfig)
  }
}

object OSMFilteringConfig {

  def apply(c: com.typesafe.config.Config, network: Network): OSMFilteringConfig = {
    val osmFilteringConfig = c.getConfig("matsim.conversion.osmFiltering")
    val pbfFile = osmFilteringConfig.getString("pbfFile")
    val outputFile = osmFilteringConfig.getString("outputFile")

    val spatialConfig = c.getConfig("beam.spatial")
    val boundingBoxBuffer = spatialConfig.getInt("boundingBoxBuffer")

    OSMFilteringConfig(pbfFile, getBoundingBoxConfig(network, boundingBoxBuffer), outputFile)

  }

  def getBoundingBoxConfig(network: Network, boundingBoxBuffer: Int = 0): BoundingBoxConfig= {
    //bbox = min Longitude , min Latitude , max Longitude , max Latitude
    val bbox = NetworkUtils.getBoundingBox(network.getNodes.values())

    val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation("EPSG:26914", "EPSG:4326")

    val left = bbox(0) //min lon - x
    val bottom = bbox(1) //min lat - y
    val right = bbox(2) // max lon - x
    val top = bbox(3) //max lat - y

    val minCoord: Coord = wgs2Utm.transform(new Coord(left, bottom))
    val maxCoord: Coord = wgs2Utm.transform(new Coord(right, top))

    val tLeft = minCoord.getX
    val tBottom = minCoord.getY
    val tRight = maxCoord.getX
    val tTop = maxCoord.getY

    //TODO add boundingBoxBuffer to bbox
    BoundingBoxConfig(tTop, tLeft, tBottom, tRight)
  }

}