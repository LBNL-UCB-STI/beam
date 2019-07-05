package beam.side.speed.parser
import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.model.BeamUberSpeed

class SpeedComparator(ways: OsmWays, uber: UberSpeed[_], fileName: String) {

  private def compare: Iterable[BeamUberSpeed] =
    ways.ways
      .withFilter(_._1 > 0)
      .map {
        case (id, speed) =>
          uber
            .speed(id)
            .fold(BeamUberSpeed(id, speed.toFloat, Float.NaN, Float.NaN, Float.NaN))(
              ws => BeamUberSpeed(id, speed.toFloat, ws.speedMedian, ws.speedAvg, ws.maxDev)
            )
      }

  def csv(): Unit = {
    val file = new File(fileName)
    val bw = new BufferedWriter(new FileWriter(file))
    bw.write("osmId,speedBeam,speedMedian,speedAvg,maxDev")
    compare.foreach { b =>
      bw.newLine()
      bw.write(b.productIterator.mkString(","))
    }
    bw.flush()
    bw.close()
  }
}

object SpeedComparator {

  def apply(ways: OsmWays, uber: UberSpeed[_], fileName: String): SpeedComparator =
    new SpeedComparator(ways, uber, fileName)
}
