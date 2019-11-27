package beam.side.route.processing
import beam.side.route.model.Url
import org.http4s.EntityDecoder

import scala.language.higherKinds

trait GHRequest[F[_]] {
  type Decoder[R] = EntityDecoder[F, R]

  def request[T : Decoder](url: Url): F[T]
}

object GHRequest {
  type Aux[F0[_], R0] = GHRequest[F0] { type Decoder[R] = EntityDecoder[F0, R0]}

  def apply[F[_], R](implicit request: GHRequest.Aux[F, R]): GHRequest[F] = request
}
