package beam.agentsim.infrastructure.geozone

import beam.agentsim.infrastructure.taz.TAZ

import scala.util.{Failure, Success, Try}
import com.typesafe.scalalogging.StrictLogging

trait GeoIndex { val value: String }

case class H3Index private (value: String, resolution: Int) extends GeoIndex

case class TAZIndex(taz: TAZ) extends GeoIndex {
  override val value: String = taz.tazId.toString
}

object H3Index extends StrictLogging {
  def apply(value: String): H3Index = new H3Index(value, H3Wrapper.getResolution(value))

  def tryCreate(value: String): Option[H3Index] = {
    Try(apply(value)) match {
      case Failure(exception) =>
        logger.warn(s"Error parsing H3Index: [$value]", exception)
        None
      case Success(value) => Some(value)
    }
  }

}
