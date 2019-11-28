package beam.side.route.processing
import beam.side.route.model.Url
import org.http4s.EntityDecoder

import scala.language.higherKinds

trait GHRequest[F[_]] {
  def request[R](url: Url)(implicit decoder: EntityDecoder[F, R]): F[R]
}

object GHRequest {
  def apply[F[_]](implicit request: GHRequest[F]): GHRequest[F] = request
}
