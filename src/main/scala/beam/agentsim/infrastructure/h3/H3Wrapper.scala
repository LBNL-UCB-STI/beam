package beam.agentsim.infrastructure.h3
import scala.collection.JavaConverters._

import com.uber.h3core.util.GeoCoord

object H3Wrapper {

  def getResolution(index: String): Int = {
    h3Core.h3GetResolution(index)
  }

  def getIndex(point: H3Point, resolution: Int): H3Index = {
    H3Index(h3Core.geoToH3Address(point.latitude, point.longitude, resolution))
  }

  def geoToH3Address(point: H3Point, resolution: Int): String = {
    h3Core.geoToH3Address(point.latitude, point.longitude, resolution)
  }

  def round(value: Double, places: Int): Double = {
    BigDecimal.valueOf(value).setScale(places, BigDecimal.RoundingMode.HALF_UP).doubleValue()
  }

  def getChildren(index: H3Index): Set[H3Index] = {
    getChildren(index, index.resolution + 1)
  }

  def getChildren(index: H3Index, resolution: Int): Set[H3Index] = {
    H3Wrapper.h3Core.h3ToChildren(index.value, Math.min(resolution, 15)).asScala.toSet.map(H3Index.apply)
  }

  private[h3] val h3Core = com.uber.h3core.H3Core.newInstance

}
