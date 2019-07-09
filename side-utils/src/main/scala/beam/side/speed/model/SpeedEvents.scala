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

case class WaySpeed(speedMedian: Option[Float], speedAvg: Option[Float], maxDev: Option[Float])

case class BeamUberSpeed(
  osmId: Long,
  speedBeam: Float,
  speedMedian: Option[Float],
  speedAvg: Option[Float],
  maxDev: Option[Float]
)

object BeamUberSpeed {
  implicit val beamUberSpeedEncoder: Encoder[BeamUberSpeed] = new Encoder[BeamUberSpeed] {
    override def apply(row: BeamUberSpeed): String =
      row.productIterator
        .map {
          case None    => ""
          case Some(s) => s.toString
          case x       => x.toString
        }
        .mkString(",")
  }
}

case class WayMetric(dateTime: LocalDateTime, speedMphMean: Float, speedMphStddev: Float) {
  override def equals(obj: Any): Boolean = obj match {
    case that: WayMetric => that.dateTime == this.dateTime
    case _               => false
  }

  override def hashCode(): Int = dateTime.##
}

case class UberDirectedWay(orig: Long, dest: Long, metrics: Seq[WayMetric])

case class OsmNodeSpeed(id: Long, orig: Long, dest: Long, speed: Float)
