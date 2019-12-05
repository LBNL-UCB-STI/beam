package beam.side.route.processing.data

import beam.side.route.model._
import beam.side.route.processing.{GHRequest, PathCompute}
import org.http4s.EntityDecoder
import zio._

import scala.util.Try

class PathComputeIO(host: String)(implicit val runtime: Runtime[_])
    extends PathCompute[({ type T[A] = RIO[zio.ZEnv, A] })#T] {

  import zio.console._

  def compute(
    trip: Trip,
    tracts: Promise[_ <: Throwable, Map[String, CencusTrack]],
    pathQueue: Queue[TripPath]
  )(
    implicit decoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, GHPaths],
    request: GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T]
  ): RIO[zio.ZEnv, Option[TripPath]] =
    for {
      trp    <- IO.effectTotal(trip)
      trc    <- tracts.await
      origin <- ZIO.fromTry(Try(trc(trp.origin)))
      dest   <- ZIO.fromTry(Try(trc(trp.dest)))
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
        .foldM(
          e =>
            Task
              .effectTotal(e)
              .flatMap(ex => putStrLn(s"Request error ${ex.getMessage}"))
              .flatMap(_ => Task.effectTotal(Option.empty)),
          path => Task.effectTotal(Option(path))
        )
      path <- Task.effectTotal(
        originReq
          .map(p => p.ways.reduce((a, b) => if (a.points.size > b.points.size) a else b))
          .map(p => TripPath(origin, dest, Multiline(p.points.toList)))
      )
      _ <- RIO.effectAsync[zio.ZEnv, Unit](c => c(path.fold(ZIO.unit)(r => pathQueue.offer(r).unit)))
    } yield path
}

object PathComputeIO {
  def apply(host: String)(implicit runtime: Runtime[_]): PathComputeIO = new PathComputeIO(host)(runtime)
}
