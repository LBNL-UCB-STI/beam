package beam.side.speed.parser
import beam.side.speed.model.BeamUberSpeed

class SpeedComparator(ways: OsmWays, uber: UberSpeed[_]) {

  private def compare: Iterable[BeamUberSpeed] =
    ways.ways
      .map {
        case (id, speed) =>
          uber
            .speed(id)
            .fold(BeamUberSpeed(id, speed.toFloat, 0, 0, 0))(
              ws => BeamUberSpeed(id, speed.toFloat, ws.speedMean, ws.speedAvg, ws.maxDev)
            )
      }

  def csv(): Unit = compare.foreach(b => println(b.productIterator.mkString(",")))
}

object SpeedComparator {
  def apply(ways: OsmWays, uber: UberSpeed[_]): SpeedComparator = new SpeedComparator(ways, uber)
}
