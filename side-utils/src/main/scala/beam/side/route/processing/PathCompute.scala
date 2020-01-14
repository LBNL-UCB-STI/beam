package beam.side.route.processing

import beam.side.route.model.{CencusTrack, GHPaths, Trip, TripPath}
import org.http4s.EntityDecoder
import zio.{Promise, Queue}

import scala.language.higherKinds

trait PathCompute[F[_]] {

  def compute(
    trip: Trip,
    tracts: Map[String, CencusTrack],
    pathQueue: Queue[TripPath]
  )(
    implicit decoder: EntityDecoder[F, GHPaths],
    request: GHRequest[F]
  ): F[Option[TripPath]]
}

object PathCompute {
  def apply[F[_]](implicit compute: PathCompute[F]): PathCompute[F] = compute
}
