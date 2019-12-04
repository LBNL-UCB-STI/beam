package beam.side.route.model

case class CencusTrack(
  state: Int,
  country: String,
  tract: String,
  population: Int,
  latitude: Double,
  longitude: Double
) {
  val id: String = s"$state$country$tract"
}

object CencusTrack {
  implicit object censusRowDecoder extends RowDecoder[CencusTrack] {
    override def apply(row: String): CencusTrack = {
      val split = row.split(",").toSeq
      CencusTrack(split.head.toInt, split(1), split(2), split(3).toInt, split(4).toDouble, split(5).toDouble)
    }
  }
}

case class Trip(origin: String, dest: String, trips: Int)

object Trip {
  implicit object tripDecoder extends RowDecoder[Trip] {
    override def apply(row: String): Trip = {
      val split = row.split(",").toSeq
      Trip(split.head, split(1), split(2).toInt)
    }
  }
}

case class TripPath(origin: CencusTrack, dest: CencusTrack, path: Multiline)

object TripPath {

  import Multiline._
  import Encoder._

  implicit val tripPathEncoder: Encoder[TripPath] = new Encoder[TripPath] {
    override def apply(row: TripPath): String = s"${row.origin.id}, ${row.dest.id}, ${row.path.row}"
  }
}
