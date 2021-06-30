package beam.agentsim.infrastructure.parking

import beam.agentsim.Resource.ReleaseParkingStall
import beam.agentsim.infrastructure.{ParkingInquiry, ParkingInquiryResponse}
import beam.utils.metrics.SimpleCounter
import com.typesafe.scalalogging.LazyLogging

abstract class ParkingNetwork[GEO: GeoLevel] extends LazyLogging {

  def processParkingInquiry(
    inquiry: ParkingInquiry,
    parallelizationCounterOption: Option[SimpleCounter] = None
  ): Option[ParkingInquiryResponse]

  def processReleaseParkingStall(release: ReleaseParkingStall)

  def getParkingZones(): Array[ParkingZone[GEO]]
}
