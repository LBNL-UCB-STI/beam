package beam.agentsim.infrastructure.geozone.aggregation

import beam.agentsim.infrastructure.geozone.H3Index

private[aggregation] trait ParkingEntry[T] {
  def id: T
  def parkingType: String
  def pricingModel: String
  def chargingPointType: String
  def reservedFor: String
  def numStalls: Long
  def feeInCents: Double
}

private[aggregation] case class CsvTazParkingEntry(
  taz: TazCoordinate,
  parkingType: String,
  pricingModel: String,
  chargingPointType: String,
  reservedFor: String,
  numStalls: Long,
  feeInCents: Double
) extends ParkingEntry[TazCoordinate] {
  override def id: TazCoordinate = taz
}

private[aggregation] case class CsvH3IndexParkingEntry(
  geoIndex: H3Index,
  parkingType: String,
  pricingModel: String,
  chargingPointType: String,
  reservedFor: String,
  numStalls: Long,
  feeInCents: Double
) extends ParkingEntry[H3Index] {
  override def id: H3Index = geoIndex
}
