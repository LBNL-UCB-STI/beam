package beam.utils

import beam.agentsim.infrastructure.geozone.WgsCoordinate

package object hereapi {
  private[hereapi] case class HerePath(coordinates: Seq[WgsCoordinate], spans: Seq[HereSpan])

  private[hereapi] case class HereSpan(offset: Int, lengthInMeters: Int, speedLimitInKph: Option[Int])
}
