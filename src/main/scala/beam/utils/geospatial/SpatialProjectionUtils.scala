package beam.utils.geospatial

import org.geotools.referencing.CRS
import org.slf4j.LoggerFactory

object SpatialProjectionUtils {

  private val logger = LoggerFactory.getLogger(this.getClass)

  // Cache to store conversion factors, avoiding redundant logging for the same CRS
  private val crsCache = scala.collection.mutable.Map.empty[String, Double]

  /**
    * Determines the conversion factor from meters to the CRS's native units.
    * Most projected CRS use meters, but some (like US state plane) use feet.
    * Results are cached to avoid redundant logging for the same CRS.
    */
  def calculateMetersToProjectedUnits(scenarioCRS: String): Double = {
    // Return cached result if already computed
    crsCache.get(scenarioCRS) match {
      case Some(factor) => return factor
      case None         => ()
    }

    try {
      val crs = CRS.decode(scenarioCRS)
      val unitString = crs.getCoordinateSystem.getAxis(0).getUnit.toString.toLowerCase

      // Parse the unit string to determine conversion factor
      val factor = unitString match {
        case s if s.contains("meter") || s.contains("metre") || s == "m" =>
          1.0
        case s if s.contains("us survey foot") || s.contains("foot_us") || s.contains("us_ft") =>
          // US survey foot to meter conversion
          1.0 / 0.304800609601219
        case s if s.contains("foot") || s.contains("feet") || s.contains("ft") =>
          // International foot to meter conversion
          1.0 / 0.3048
        case s if s.contains("kilometer") || s.contains("kilometre") || s == "km" =>
          1000.0
        case s if s.contains("mile") || s == "mi" =>
          // International mile
          1.0 / 1609.344
        case s if s.contains("yard") || s == "yd" =>
          1.0 / 0.9144
        case _ =>
          // Try to check if it's already a linear unit by checking for degree
          if (unitString.contains("degree") || unitString.contains("°")) {
            logger.error(
              s"CRS ${scenarioCRS} uses angular units ($unitString). This will not work correctly for distance calculations!"
            )
            1.0
          } else {
            logger.warn(s"Unknown unit '$unitString' for CRS ${scenarioCRS}, assuming meters")
            1.0
          }
      }

      // Log only on first computation for this CRS
      logger.info(
        s"CRS ${scenarioCRS} uses unit: $unitString, conversion factor: $factor meters -> CRS units"
      )

      // Cache the result
      crsCache.put(scenarioCRS, factor)
      factor

    } catch {
      case e: Exception =>
        logger.error(s"Failed to determine units for CRS ${scenarioCRS}, assuming meters", e)
        1.0
    }
  }

}
