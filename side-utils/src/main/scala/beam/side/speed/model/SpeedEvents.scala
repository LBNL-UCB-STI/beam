package beam.side.speed.model

import java.time.LocalDateTime

import scala.reflect.runtime.universe._

sealed trait Decoder[T] {
  def apply(row: String): T
}

sealed trait Encoder[T <: Product] {
  def header(implicit tg: TypeTag[T]): List[String] =
    typeOf[T].members
      .collect {
        case m: MethodSymbol if m.isCaseAccessor => m.name.toString
      }
      .toList
      .reverse

  def apply(row: T): String
}

object Encoder {
  implicit class EncoderSyntax[T <: Product](data: T) {
    def header(implicit tg: TypeTag[T], enc: Encoder[T]): String =
      enc.header.mkString(",")
    def row(implicit enc: Encoder[T]): String = enc.apply(data)
  }

  def apply[T <: Product](implicit Enc: Encoder[T]): Encoder[T] = Enc
}

object SpeedEvents {
  implicit class StringToObj[T <: Product](row: String)(
      implicit dec: Decoder[T]) {
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
  implicit val uberSpeedDecoder: Decoder[UberSpeedEvent] =
    new Decoder[UberSpeedEvent] {
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
  implicit val uberOsmWaysDecoder: Decoder[UberOsmWays] =
    new Decoder[UberOsmWays] {
      override def apply(row: String): UberOsmWays = {
        val Seq(s, o) = row.split(',').toSeq
        UberOsmWays(s, o.toLong)
      }
    }
}

case class UberOsmNode(segmentId: String, osmNodeId: Long)

object UberOsmNode {
  implicit val uberOsmNodeDecoder: Decoder[UberOsmNode] =
    new Decoder[UberOsmNode] {
      override def apply(row: String): UberOsmNode = {
        val Seq(s, o) = row.split(',').toSeq
        UberOsmNode(s, o.toLong)
      }
    }
}

case class BeamSpeed(osmId: Long, speed: Float, length: Double)

object BeamSpeed {
  implicit val beamSpeedDecoder: Decoder[BeamSpeed] = new Decoder[BeamSpeed] {
    override def apply(row: String): BeamSpeed = {
      val Seq(id, s, l) = row.split(',').toSeq
      BeamSpeed(id.toLong, s.toFloat, l.toDouble)
    }
  }
  implicit val beamSpeedEncoder: Encoder[BeamSpeed] = new Encoder[BeamSpeed] {
    override def apply(row: BeamSpeed): String =
      row.productIterator
        .map {
          case None    => ""
          case Some(s) => s.toString
          case x       => x.toString
        }
        .mkString(",")
  }
}

case class WaySpeed(speedMedian: Option[Float],
                    speedAvg: Option[Float],
                    maxDev: Option[Float])

case class BeamUberSpeed(
    osmId: Long,
    j1: Long,
    j2: Long,
    speedBeam: Float,
    speedMedian: Option[Float],
    speedAvg: Option[Float],
    maxDev: Option[Float],
    cat: String
)

case class LinkSpeed(
    linkId: Int,
    capacity: Option[Double],
    freeSpeed: Option[Float],
    length: Option[Double]
)

object LinkSpeed {
  implicit val linkSpeedSpeedEncoder: Encoder[LinkSpeed] =
    new Encoder[LinkSpeed] {
      override def apply(row: LinkSpeed): String =
        row.productIterator
          .map {
            case None    => ""
            case Some(s) => s.toString
            case x       => x.toString
          }
          .mkString(",")
    }
}

object BeamUberSpeed {
  implicit val beamUberSpeedEncoder: Encoder[BeamUberSpeed] =
    new Encoder[BeamUberSpeed] {
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

case class WayMetrics(sId: String, metrics: Seq[WayMetric]) {
  override def equals(obj: Any): Boolean = obj match {
    case that: WayMetrics => that.sId == this.sId
    case _                => false
  }
  override def hashCode(): Int = sId.##
}

case class WayMetric(dateTime: LocalDateTime,
                     speedMphMean: Float,
                     speedMphStddev: Float) {
  override def equals(obj: Any): Boolean = obj match {
    case that: WayMetric => that.dateTime == this.dateTime
    case _               => false
  }

  override def hashCode(): Int = dateTime.##
}

case class UberWay(segmentId: String,
                   startJunctionId: String,
                   endJunctionId: String)

case class UberDirectedWay(orig: Long,
                           dest: Long,
                           wayId: String,
                           metrics: Seq[WayMetric])

case class OsmNodeSpeed(eId: Int,
                        id: Long,
                        orig: Long,
                        dest: Long,
                        speed: Float,
                        cat: String,
                        lenght: Double)
