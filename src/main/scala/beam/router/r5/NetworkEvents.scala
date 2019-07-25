package beam.router.r5
import beam.utils.Decoder

case class SpeedDataEvent(osmId: Long, speedAvg: Double)

object SpeedDataEvent {
  implicit val speedDecoder: Decoder[SpeedDataEvent] = (row: String) => {
    val Seq(osmId, _, _, _, _, speedAvg, _, _) = row.split(',').toSeq
    SpeedDataEvent(osmId.toLong, speedAvg.toDouble)
  }
}
