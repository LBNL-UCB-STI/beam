package beam.analysis.carridestats

case class CarRideStatsSample(sampleSize: Int, sampleSeed: Option[Int]) extends CarRideStatsParam {

  override def arguments: Seq[String] = {
    val a = Seq("--sampleSize", sampleSize.toString)
    val b = sampleSeed match {
      case Some(value) => Seq("--sampleSeed", value.toString)
      case None        => Seq.empty
    }
    a ++ b
  }

}
