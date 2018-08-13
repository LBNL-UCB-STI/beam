package conversion

import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import scala.util.Try

case class HouseholdIncome(currency: String, period: String, value: Int)
case class ConversionConfig( outputDirectory: String, localCRS: String,
                             matsimNetworkFile: String, shapeConfig: Option[ShapeConfig] = None,
                             populationInput: String, income: HouseholdIncome, generateVehicles: Boolean = false,
                             transitVehiclesInput: Option[String] = None,
                             vehiclesInput: Option[String] = None)

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

    val populationInput = matsimConversionConfig.getString("populationInput")
    val generateVehicles = matsimConversionConfig.getBoolean("generateVehicles")

    val transitVehiclesPath = Try(c.getString("matsim.modules.transit.vehiclesFile")).toOption

    //    val vehiclesInput = matsimConversionConfig.getString("vehiclesInput")
    val vehiclesInput = Try(matsimConversionConfig.getString("vehiclesInput")).toOption

    val defaultHouseholdIncomeConfig = matsimConversionConfig.getConfig("defaultHouseholdIncome")
    val incomeCurrency = defaultHouseholdIncomeConfig.getString("currency")
    val incomePeriod = defaultHouseholdIncomeConfig.getString("period")
    val incomeValue = defaultHouseholdIncomeConfig.getInt("value")
    val income = HouseholdIncome(incomeCurrency, incomePeriod, incomeValue)


    ConversionConfig(output, localCRS, matsimNetworkFile,
      mShapeConfig, populationInput, income, generateVehicles,
      transitVehiclesPath, vehiclesInput)
  }
}

object OSMFilteringConfig {

  def apply(c: com.typesafe.config.Config, network: Network): OSMFilteringConfig = {
    val osmFilteringConfig = c.getConfig("matsim.conversion.osmFiltering")
    val pbfFile = osmFilteringConfig.getString("pbfFile")
    val outputFile = osmFilteringConfig.getString("outputFile")

    val spatialConfig = c.getConfig("beam.spatial")
    val boundingBoxBuffer = spatialConfig.getInt("boundingBoxBuffer")
    val localCRS = spatialConfig.getString("localCRS")

    OSMFilteringConfig(pbfFile, getBoundingBoxConfig(network, localCRS, boundingBoxBuffer), outputFile)

  }

  def getBoundingBoxConfig(network: Network, localCrs: String, boundingBoxBuffer: Int = 0): BoundingBoxConfig= {
    //bbox = min Longitude , min Latitude , max Longitude , max Latitude
    val bbox = NetworkUtils.getBoundingBox(network.getNodes.values())

    val left = bbox(0) //min lon - x
    val bottom = bbox(1) //min lat - y
    val right = bbox(2) // max lon - x
    val top = bbox(3) //max lat - y

    //From local csr to UTM
    val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation(localCrs, "EPSG:4326")
    val minCoord: Coord = wgs2Utm.transform(new Coord(left, bottom))
    val maxCoord: Coord = wgs2Utm.transform(new Coord(right, top))

    val env = new Envelope(minCoord.getX, maxCoord.getX, minCoord.getY, maxCoord.getY)
    env.expandBy(boundingBoxBuffer)

    val tLeft = env.getMinX
    val tBottom = env.getMinY
    val tRight = env.getMaxX
    val tTop = env.getMaxY

    BoundingBoxConfig(tTop, tLeft, tBottom, tRight)
  }

}