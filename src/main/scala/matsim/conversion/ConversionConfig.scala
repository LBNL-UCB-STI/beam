package matsim.conversion

case class ConversionConfig( outputDirectory: String, shapeFile: Option[String] = None, tazIDFieldName: Option[String] = None )

object ConversionConfig {

  def getConfig() = {
    ConversionConfig("D:\\tmp\\output", Some("D:\\tmp\\shapeFiles\\tz06_d00.shp"), Some("TZ06_D00_I"))
  }
}