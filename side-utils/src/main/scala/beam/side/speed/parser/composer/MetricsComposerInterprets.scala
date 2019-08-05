package beam.side.speed.parser.composer
import java.time.DayOfWeek

import beam.side.speed.model._
import beam.side.speed.parser.Median
import beam.side.speed.parser.operation.ObservationComposer

trait MetricsComposerInterprets {

  implicit object WayMetricsSpeedComposer extends ObservationComposer[Option, Seq[WayMetrics], UberWaySpeed] {
    override def compose(input: Seq[WayMetrics]): Option[UberWaySpeed] =
      Some(maxWeek(input.map(i => (i.metrics.map(_.speedMphMean).max, i.metrics.size.toDouble))))

    private def maxWeek(metrics: Seq[(Float, Double)]): UberWaySpeed =
      UberWaySpeed(
        Seq(
          UberDaySpeed(
            DayOfWeek.TUESDAY,
            metrics
              .map { case (f, s) => (f * 1.60934 / 3.6).toFloat -> s }
              .map { case (f, s) => UberHourSpeed(10, f, f, s.toFloat, f) }
              .toList
          )
        )
      )
  }

  implicit object WayMetricSpeedComposer extends ObservationComposer[Option, Seq[WayMetric], UberWaySpeed] {
    override def compose(
      input: Seq[WayMetric]
    ): Option[UberWaySpeed] = Some(dropToWeek(input))

    private def dropToWeek(metrics: Seq[WayMetric]): UberWaySpeed = {
      val week = metrics
        .groupBy(e => (e.dateTime.getHour, e.dateTime.getDayOfWeek))
        .toSeq
        .map {
          case ((h, dw), g) =>
            val speedMax = g.map(_.speedMphMean).max * 1.60934 / 3.6
            val speedAvg = g.map(_.speedMphMean).sum * 1.60934 / (3.6 * g.size)
            val speedMedian = Median.findMedian(g.map(_.speedMphMean).toArray) * 1.60934 / 3.6
            val devMax = g.size
            (dw, UberHourSpeed(h, speedMedian.toFloat, speedAvg.toFloat, devMax.toFloat, speedMax.toFloat))
        }
        .groupBy(_._1)
        .mapValues(s => s.map(_._2))
        .map {
          case (d, uhs) => UberDaySpeed(d, uhs.toList)
        }
      UberWaySpeed(week.toSeq)
    }
  }

  implicit object WayMetricsMaxPartsComposer extends ObservationComposer[Option, Seq[WayMetrics], Seq[Float]] {
    override def compose(
      input: Seq[WayMetrics]
    ): Option[Seq[Float]] = Some(input.foldLeft(Seq[Float]())((acc, m) => acc :+ m.metrics.map(_.speedMphMean).max))
  }

  implicit object WayMetricsDaySpeedComposer extends ObservationComposer[Option, Seq[WayMetrics], Seq[UberDaySpeed]] {
    override def compose(input: Seq[WayMetrics]): Option[Seq[UberDaySpeed]] =
      Some(
        input
          .foldLeft(Seq[WayMetric]())((acc, e2) => acc ++ e2.metrics)
          .groupBy(e => (e.dateTime.getHour, e.dateTime.getDayOfWeek))
          .toSeq
          .map {
            case ((h, dw), g) =>
              val speedMax = g.map(_.speedMphMean).max * 1.60934 / 3.6
              val speedAvg = g.map(_.speedMphMean).sum * 1.60934 / (3.6 * g.size)
              val devMax = g.map(_.speedMphStddev).max * 1.60934 / 3.6
              val speedMedian = Median.findMedian(g.map(_.speedMphMean).toArray) * 1.60934 / 3.6
              (dw, UberHourSpeed(h, speedMedian.toFloat, speedAvg.toFloat, devMax.toFloat, speedMax.toFloat))
          }
          .groupBy(_._1)
          .mapValues(s => s.map(_._2))
          .map {
            case (d, uhs) => UberDaySpeed(d, uhs.toList)
          }
          .toSeq
      )
  }
}
