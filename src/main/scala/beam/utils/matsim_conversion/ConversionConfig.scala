package beam.utils.matsim_conversion

import com.vividsolutions.jts.geom.Envelope
import org.matsim.api.core.v01.Coord
import org.matsim.api.core.v01.network.Network
import org.matsim.core.network.NetworkUtils
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation

import scala.util.Try

case class HouseholdIncome(currency: String, period: String, value: Int)

case class ConversionConfig(
  scenarioName: String,
  scenarioDirectory: String,
  populationInput: String,
  income: HouseholdIncome,
  localCRS: String,
  matsimNetworkFile: String,
  osmFile: String,
  boundingBoxBuffer: Int,
  generateVehicles: Boolean = false,
  shapeConfig: Option[ShapeConfig] = None,
//                             transitVehiclesInput: Option[String] = None,
  vehiclesInput: Option[String] = None
)

case class ShapeConfig(shapeFile: String, tazIDFieldName: String)

//case class OSMFilteringConfig(pbfFile: String, boundingBox: BoundingBoxConfig, outputFile: String)

case class BoundingBoxConfig(top: Double, left: Double, bottom: Double, right: Double)

object ConversionConfig {

  def apply(c: com.typesafe.config.Config): ConversionConfig = {
    val matsimConversionConfig = c.getConfig("matsim.conversion")
    val simName = c.getString("beam.agentsim.simulationName")
    val scenarioDir = matsimConversionConfig.getString("scenarioDirectory")
    val matsimNetworkFile =
      s"$scenarioDir/conversion-input/${matsimConversionConfig.getString("matsimNetworkFile")}"

    val spatialConfig = c.getConfig("beam.spatial")

    //TODO try to detect coordinate projection from MATSim network and population file
    val localCRS = spatialConfig.getString("localCRS")
    val boundingBoxBuffer = spatialConfig.getInt("boundingBoxBuffer")

    val mShapeConfig = if (matsimConversionConfig.hasPathOrNull("shapeConfig")) {
      val shapeConfig = matsimConversionConfig.getConfig("shapeConfig")
      val shapeFile = s"$scenarioDir/conversion-input/${shapeConfig.getString("shapeFile")}"
      val tazIdField = shapeConfig.getString("tazIdFieldName")
      Some(ShapeConfig(shapeFile, tazIdField))
    } else
      None

    val populationInput =
      s"$scenarioDir/conversion-input/${matsimConversionConfig.getString("populationFile")}"
    val generateVehicles = matsimConversionConfig.getBoolean("generateVehicles")
    val osmFile = s"$scenarioDir/conversion-input/${matsimConversionConfig.getString("osmFile")}"

//    val transitVehiclesPath = Try(c.getString("matsim.modules.transit.vehiclesFile")).toOption

    //    val vehiclesInput = matsimConversionConfig.getString("vehiclesInput")
    val vehiclesInput = Try(s"$scenarioDir/${matsimConversionConfig.getString("vehiclesInput")}").toOption

    val defaultHouseholdIncomeConfig = matsimConversionConfig.getConfig("defaultHouseholdIncome")
    val incomeCurrency = defaultHouseholdIncomeConfig.getString("currency")
    val incomePeriod = defaultHouseholdIncomeConfig.getString("period")
    val incomeValue = defaultHouseholdIncomeConfig.getInt("value")
    val income = HouseholdIncome(incomeCurrency, incomePeriod, incomeValue)

    ConversionConfig(
      simName,
      scenarioDir,
      populationInput,
      income,
      localCRS,
      matsimNetworkFile,
      osmFile,
      boundingBoxBuffer,
      generateVehicles,
      mShapeConfig, /*transitVehiclesPath,*/
      vehiclesInput
    )
  }

  def getBoundingBoxConfig(
    network: Network,
    localCrs: String,
    boundingBoxBuffer: Int = 0
  ): BoundingBoxConfig = {
    //bbox = min Longitude , min Latitude , max Longitude , max Latitude
    val bbox = NetworkUtils.getBoundingBox(network.getNodes.values())

    val left = bbox(0) //min lon - x
    val bottom = bbox(1) //min lat - y
    val right = bbox(2) // max lon - x
    val top = bbox(3) //max lat - y

    val env = new Envelope(left, right, bottom, top)
    env.expandBy(boundingBoxBuffer)

    //From local csr to UTM
    val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation(localCrs, "EPSG:4326")
    val minCoord: Coord = wgs2Utm.transform(new Coord(env.getMinX, env.getMinY))
    val maxCoord: Coord = wgs2Utm.transform(new Coord(env.getMaxX, env.getMaxY))

    val tLeft = minCoord.getX
    val tBottom = minCoord.getY
    val tRight = maxCoord.getX
    val tTop = maxCoord.getY

    BoundingBoxConfig(tTop, tLeft, tBottom, tRight)
  }

}

//object OSMFilteringConfig {
//
//  def apply(c: com.typesafe.config.Config, network: Network): OSMFilteringConfig = {
//    val osmFilteringConfig = c.getConfig("matsim.conversion.osmFiltering")
//    val pbfFile = osmFilteringConfig.getString("pbfFile")
//    val outputFile = osmFilteringConfig.getString("outputFile")
//
//    val spatialConfig = c.getConfig("beam.spatial")
//    val boundingBoxBuffer = spatialConfig.getInt("boundingBoxBuffer")
//    val localCRS = spatialConfig.getString("localCRS")
//
//    OSMFilteringConfig(pbfFile, getBoundingBoxConfig(network, localCRS, boundingBoxBuffer), outputFile)
//
//  }
//
//  def getBoundingBoxConfig(network: Network, localCrs: String, boundingBoxBuffer: Int = 0): BoundingBoxConfig= {
//    //bbox = min Longitude , min Latitude , max Longitude , max Latitude
//    val bbox = NetworkUtils.getBoundingBox(network.getNodes.values())
//
//    val left = bbox(0) //min lon - x
//    val bottom = bbox(1) //min lat - y
//    val right = bbox(2) // max lon - x
//    val top = bbox(3) //max lat - y
//
//    //From local csr to UTM
//    val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation(localCrs, "EPSG:4326")
//    val minCoord: Coord = wgs2Utm.transform(new Coord(left, bottom))
//    val maxCoord: Coord = wgs2Utm.transform(new Coord(right, top))
//
//    val env = new Envelope(minCoord.getX, maxCoord.getX, minCoord.getY, maxCoord.getY)
//    env.expandBy(boundingBoxBuffer)
//
//    val tLeft = env.getMinX
//    val tBottom = env.getMinY
//    val tRight = env.getMaxX
//    val tTop = env.getMaxY
//
//    BoundingBoxConfig(tTop, tLeft, tBottom, tRight)
//  }

//}
