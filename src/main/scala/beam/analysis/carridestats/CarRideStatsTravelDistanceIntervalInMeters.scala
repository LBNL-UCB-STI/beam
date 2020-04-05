package beam.analysis.carridestats

case class CarRideStatsTravelDistanceIntervalInMeters(start: Double, end: Double) extends CarRideStatsParam {
  override def arguments: Seq[String] = {
    Seq("--travelDistanceIntervalInMeters", s"[$start,$end]")
  }
}
