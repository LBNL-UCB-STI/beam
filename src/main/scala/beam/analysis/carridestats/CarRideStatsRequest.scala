package beam.analysis.carridestats

case class CarRideStatsRequest(
  input: InputContentParam,
  sample: Option[CarRideStatsSample],
  travelDistanceIntervalInMeters: Option[CarRideStatsTravelDistanceIntervalInMeters],
  departureTimeIntervalInSeconds: Option[CarRideStatsDepartureTimeIntervalInSeconds],
  areaBoundBox: Option[CarRideStatsFilterAreaBoundBox]
) {

  def args: Seq[String] = {
    input.arguments ++
    sample.map(_.arguments).getOrElse(Seq.empty) ++
    travelDistanceIntervalInMeters.map(_.arguments).getOrElse(Seq.empty) ++
    departureTimeIntervalInSeconds.map(_.arguments).getOrElse(Seq.empty) ++
    areaBoundBox.map(_.arguments).getOrElse(Seq.empty)
  }

}
