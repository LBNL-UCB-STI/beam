package beam.agentsim.infrastructure.geozone

import scala.util.{Failure, Success, Try}

import com.typesafe.scalalogging.StrictLogging

case class GeoIndex private[geozone] (value: String, resolution: Int)

object GeoIndex extends StrictLogging {
  def apply(value: String): GeoIndex = new GeoIndex(value, H3Wrapper.getResolution(value))

  def tryCreate(value: String): Option[GeoIndex] = {
    Try(apply(value)) match {
      case Failure(exception) =>
        logger.warn(s"Error parsing GeoIndex: [$value]", exception)
        None
      case Success(value) => Some(value)
    }
  }

}
