package beam.side.route.processing.data

import beam.side.route.model.{CencusTrack, Coordinate, GHPaths, Multiline, Trip, TripPath, Url}
import beam.side.route.processing.{GHRequest, PathCompute}
import org.http4s.EntityDecoder
import zio._

import scala.util.Try

class PathComputeIO(host: String)(implicit val runtime: Runtime[_])
    extends PathCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T] {

  import zio.console._

  def compute(
    trip: Trip,
    tracts: Promise[_ <: Throwable, Map[String, CencusTrack]]
  )(
    implicit decoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths],
    request: GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T]
  ): RIO[zio.ZEnv, TripPath] =
    for {
      trp            <- IO.effectTotal(trip)
      trc            <- tracts.await
      (origin, dest) <- ZIO.fromTry(Try(trc(trp.origin))) &&& ZIO.fromTry(Try(trc(trp.dest)))
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
      originReq <- GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T]
        .request[GHPaths](url)
        .tapError(e => putStrLn(e.getMessage))
      ways <- Task.effectTotal(originReq.ways.reduce((a, b) => if (a.points.size > b.points.size) a else b))
    } yield
      TripPath(
        Coordinate(origin.longitude, origin.latitude),
        Coordinate(dest.longitude, dest.latitude),
        Multiline(ways.points.toList)
      )
}

object PathComputeIO {
  def apply(host: String)(implicit runtime: Runtime[_]): PathComputeIO = new PathComputeIO(host)(runtime)
}
