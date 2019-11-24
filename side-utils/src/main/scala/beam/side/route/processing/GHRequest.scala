package beam.side.route.processing
import beam.side.route.model.Url

import scala.language.higherKinds

trait GHRequest[F[_]] {
  def request(url: Url): F[String]
}

object GHRequest {
  def apply[F[_]](implicit request: GHRequest[F]): GHRequest[F] = request
}
