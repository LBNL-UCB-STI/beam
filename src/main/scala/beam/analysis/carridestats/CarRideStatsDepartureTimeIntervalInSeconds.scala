package beam.analysis.carridestats

case class CarRideStatsDepartureTimeIntervalInSeconds(start: Double, end: Double) extends CarRideStatsParam {
  override def arguments: Seq[String] = {
    Seq("--departureTimeIntervalInSeconds", s"[$start,$end]")
  }
}
