package beam.side.route.processing

import beam.side.route.model.CencusTrack
import zio.Promise

import scala.language.higherKinds

trait CencusTractDictionary[F[_], BUF[_]] {

  def compose(cencusTrackPath: String)(
    implicit dataLoader: DataLoader[F, BUF]
  ): F[Promise[Throwable, (Set[Int], Map[String, CencusTrack])]]
}

object CencusTractDictionary {

  def apply[F[_], BUF[_]](implicit dictionary: CencusTractDictionary[F, BUF]): CencusTractDictionary[F, BUF] =
    dictionary
}
