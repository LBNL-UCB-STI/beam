package beam.side.speed.parser

import java.nio.file.Path

import beam.side.speed.model.{UberDaySpeed, UberHourSpeed, UberSpeedEvent, UberWaySpeed}

import scala.annotation.tailrec

class UberSpeedRaw(path: String) extends DataLoader[UberSpeedEvent] with UnarchivedSource {
  import beam.side.speed.model.UberSpeedEvent._

  lazy val speeds = load(Path.of(path))
    .foldLeft(Map[String, Seq[UberSpeedEvent]]())((acc, s) => acc + (s.segmentId -> (acc.getOrElse(s.segmentId, Seq()) :+ s)))
    .map {
      case (segmentId, grouped) => dropToWeek(segmentId, grouped)
    }

  private def dropToWeek(segmentId: String, junctions: Seq[UberSpeedEvent]): UberWaySpeed = {
    val week = junctions.groupBy(e => (e.dateTime.getHour, e.dateTime.getDayOfWeek))
      .map {
      case ((h, dw), g) =>
        val speedAvg = g.map(_.speedMphMean).sum / g.size
        val devMax = g.map(_.speedMphStddev).max
        val speedMedian = findMedian(g.map(_.speedMphMean).toArray)
        (dw, UberHourSpeed(h, speedMedian, speedAvg, devMax))
    }.groupBy(_._1).mapValues(_.values)
      .map {
        case (d, uhs) => UberDaySpeed(d, uhs.toSeq)
      }
    UberWaySpeed(segmentId, week.toSeq)
  }

  @tailrec private def findKMedian(arr: Array[Float], k: Int)(implicit choosePivot: Array[Float] => Float): Float = {
    val a = choosePivot(arr)
    val (s, b) = arr partition (a >)
    if (s.length == k) a
    // The following test is used to avoid infinite repetition
    else if (s.isEmpty) {
      val (s, b) = arr partition (a ==)
      if (s.length > k) a
      else findKMedian(b, k - s.length)
    } else if (s.length < k) findKMedian(b, k - s.length)
    else findKMedian(s, k)
  }

  private def findMedian(arr: Array[Float])(implicit choosePivot: Array[Float] => Float) =
    findKMedian(arr, (arr.length - 1) / 2)

  implicit private def chooseRandomPivot(arr: Array[Float]): Float = arr(scala.util.Random.nextInt(arr.length))
}

object UberSpeedRaw {
  def apply(path: String): UberSpeedRaw = new UberSpeedRaw(path)
}
