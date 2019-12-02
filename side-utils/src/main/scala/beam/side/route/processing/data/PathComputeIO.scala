package beam.side.route.processing.data

import beam.side.route.model.{CencusTrack, Coordinate, GHPaths, Multiline, Trip, TripPath, Url}
import beam.side.route.processing.GHRequest
import org.http4s.EntityDecoder
import zio._

class PathComputeIO(host: String) {

  def compute(
    trip: Trip,
    tracts: Promise[Throwable, Map[String, CencusTrack]]
  )(
    implicit decoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths],
    request: GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T]
  ): RIO[zio.ZEnv, TripPath] =
    for {
      trp    <- IO.effectTotal(trip)
      trc    <- tracts.await
      origin <- IO.fromOption(trc.get(trp.origin))
      dest   <- IO.fromOption(trc.get(trp.dest))
      url = Url(
        host,
        "route",
        Seq(
          "point"          -> Seq((origin.latitude, origin.longitude), (dest.latitude, dest.longitude)),
          "vehicle"        -> "car",
          "points_encoded" -> false,
          "type"           -> "json",
          "calc_points"    -> true
        )
      )
      originReq <- GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T].request[GHPaths](url)
      ways      <- IO.fromOption(originReq.ways.headOption)
    } yield
      TripPath(
        Coordinate(origin.longitude, origin.latitude),
        Coordinate(dest.longitude, dest.latitude),
        Multiline(ways.points.toList)
      )
}
