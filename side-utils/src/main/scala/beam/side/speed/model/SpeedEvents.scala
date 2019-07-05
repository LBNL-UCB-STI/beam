package beam.side.speed.model

import java.time.LocalDateTime

sealed trait Decoder[T <: Product] {
  def apply(row: String): T
}

sealed trait Encoder[T <: Product] {
  def apply(row: T): String
}

object SpeedEvents {
  implicit class StringToObj[T <: Product](row: String)(implicit dec: Decoder[T]) {
    def osm: T = dec.apply(row)
  }
}

case class UberSpeedEvent(
  dateTime: LocalDateTime,
  segmentId: String,
  startJunctionId: String,
  endJunctionId: String,
  speedMphMean: Float,
  speedMphStddev: Float
)

object UberSpeedEvent {
  implicit val uberSpeedDecoder: Decoder[UberSpeedEvent] = new Decoder[UberSpeedEvent] {
    override def apply(row: String): UberSpeedEvent = {
      val Seq(y, m, d, h, u, s, sj, ej, smm, smd) = row.split(',').toSeq
      UberSpeedEvent(
        LocalDateTime.of(y.toInt, m.toInt, d.toInt, h.toInt, 0),
        s,
        sj,
        ej,
        smm.toFloat,
        smd.toFloat
      )
    }
  }
}

case class UberOsmWays(segmentId: String, osmWayId: Long)

object UberOsmWays {
  implicit val uberOsmWaysDecoder: Decoder[UberOsmWays] = new Decoder[UberOsmWays] {
    override def apply(row: String): UberOsmWays = {
      val Seq(s, o) = row.split(',').toSeq
      UberOsmWays(s, o.toLong)
    }
  }
}

case class UberOsmNode(segmentId: String, osmNodeId: Long)

object UberOsmNode {
  implicit val uberOsmNodeDecoder: Decoder[UberOsmNode] = new Decoder[UberOsmNode] {
    override def apply(row: String): UberOsmNode = {
      val Seq(s, o) = row.split(',').toSeq
      UberOsmNode(s, o.toLong)
    }
  }
}

case class WaySpeed(speedMedian: Float, speedAvg: Float, maxDev: Float)

case class BeamUberSpeed(osmId: Long, speedBeam: Float, speedMedian: Float, speedAvg: Float, maxDev: Float)

object BeamUberSpeed {
  implicit val beamUberSpeedEncoder: Encoder[BeamUberSpeed] = new Encoder[BeamUberSpeed] {
    override def apply(row: BeamUberSpeed): String = row.toString
  }
}

case class WayMetric(dateTime: LocalDateTime, speedMphMean: Float, speedMphStddev: Float)

case class UberDirectedWay(orig: Long, dest: Long, metrics: Seq[WayMetric])
