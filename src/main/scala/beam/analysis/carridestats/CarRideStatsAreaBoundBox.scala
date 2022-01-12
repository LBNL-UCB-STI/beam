package beam.analysis.carridestats

case class CarRideStatsPoint(x: Double, y: Double) {
  override def toString: String = s"($x,$y)"
}

case class CarRideStatsFilterAreaBoundBox(topLeft: CarRideStatsPoint, bottomRight: CarRideStatsPoint)
    extends CarRideStatsParam {

  override def arguments: Seq[String] = {
    Seq("--areaBoundBox", s"[$topLeft,$bottomRight]")
  }
}
