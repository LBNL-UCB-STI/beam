package beam.agentsim.infrastructure.geozone.aggregation

import beam.agentsim.infrastructure.geozone.GeoIndex

private[aggregation] trait ParkingEntry[T] {
  def id: T
  def parkingType: String
  def pricingModel: String
  def chargingType: String
  def reservedFor: String
  def numStalls: Long
  def feeInCents: Double
}

private[aggregation] case class CsvTazParkingEntry(
  taz: TazCoordinate,
  parkingType: String,
  pricingModel: String,
  chargingType: String,
  reservedFor: String,
  numStalls: Long,
  feeInCents: Double,
) extends ParkingEntry[TazCoordinate] {
  override def id: TazCoordinate = taz
}

private[aggregation] case class CsvGeoIndexParkingEntry(
  geoIndex: GeoIndex,
  parkingType: String,
  pricingModel: String,
  chargingType: String,
  reservedFor: String,
  numStalls: Long,
  feeInCents: Double,
) extends ParkingEntry[GeoIndex] {
  override def id: GeoIndex = geoIndex
}
