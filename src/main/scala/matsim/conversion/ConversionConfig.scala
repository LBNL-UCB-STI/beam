package matsim.conversion

case class ConversionConfig( outputDirectory: String, shapeFile: Option[String] = None, tazIDFieldName: Option[String] = None )

case class OSMFilteringConfig(pbfFile: String, boundingBox: BoundingBoxConfig, outputFile: String)

case class BoundingBoxConfig(top: Double, left: Double, bottom: Double, right: Double)

object ConversionConfig {

  def getConversionConfig() = {
    ConversionConfig("D:\\tmp\\output", Some("D:\\tmp\\shapeFiles\\tz06_d00.shp"), Some("TZ06_D00_I"))
  }

  def getOSMFilteringConfig() = {
    OSMFilteringConfig("input-path",
      BoundingBoxConfig(0, 0, 0, 0),
      "output-pah"
    )
  }
}