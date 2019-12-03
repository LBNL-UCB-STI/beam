package beam.side.route.processing

import beam.side.route.model.{CencusTrack, GHPaths, TripPath}
import org.http4s.EntityDecoder

import scala.language.higherKinds
import zio._

trait ODCompute[F[_]] {

  def pairTrip(odPairsPath: Option[String],
               tracts: Promise[_ <: Throwable, Map[String, CencusTrack]]
              )(
                implicit pathCompute: PathCompute[F],
                pathEncoder: EntityDecoder[F, GHPaths],
                ghRequest: GHRequest[F],
                dataLoader: DataLoader[F, Queue]
              ): F[Queue[TripPath]]
}

object ODCompute {
  def apply[F[_]](implicit compute: ODCompute[F]): ODCompute[F] = compute
}
