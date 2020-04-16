package beam.agentsim.infrastructure.geozone.taz

private[taz] case class TazParkingEntry(
  taz: TazCoordinate,
  parkingType: String,
  pricingModel: String,
  chargingType: String,
  reservedFor: String,
  numStalls: Long,
  feeInCents: Double,
)
