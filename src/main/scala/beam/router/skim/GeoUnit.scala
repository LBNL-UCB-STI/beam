package beam.router.skim

import beam.sim.common.GeoUtils
import org.matsim.api.core.v01.Coord

sealed trait GeoUnit {
  def id: String
  def center: Coord
  def areaInSquareMeters: Double
}

object GeoUnit {
  final case class TAZ(override val id: String, override val center: Coord, override val areaInSquareMeters: Double)
      extends GeoUnit {
    require(GeoUtils.isInvalidWgsCoordinate(center), s"Provided coordinate $center is not in UTM!")
  }
  final case class H3(override val id: String, override val center: Coord, override val areaInSquareMeters: Double)
      extends GeoUnit {
    require(GeoUtils.isInvalidWgsCoordinate(center), s"Provided coordinate $center is not in UTM")
  }
}
