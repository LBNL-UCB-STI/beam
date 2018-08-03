package conversion

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

  def apply(c: com.typesafe.config.Config): OSMFilteringConfig = {
    val osmFilteringConfig = c.getConfig("matsim.conversion.osmFiltering")
    val pbfFile = osmFilteringConfig.getString("pbfFile")
    val outputFile = osmFilteringConfig.getString("outputFile")

    val spatialConfig = c.getConfig("beam.spatial")
    val boundingBoxBuffer = spatialConfig.getInt("boundingBoxBuffer")

    OSMFilteringConfig(pbfFile, getBoundingBoxConfig(boundingBoxBuffer), outputFile)
  }

  def getBoundingBoxConfig(boundingBoxBuffer: Int) = {
    //TODO get bounding box from x/y extents of every node in the network.
    val top = 0
    val bottom = 0
    val left = 0
    val right = 0

    BoundingBoxConfig(top + boundingBoxBuffer, left + boundingBoxBuffer, bottom + boundingBoxBuffer, right + boundingBoxBuffer)
  }

}