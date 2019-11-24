package beam.side.route.processing
import beam.side.route.model.Instruction

import scala.language.higherKinds

trait RequestMaterializer[F[_]] extends (String => F[Instruction])

object RequestMaterializer {
  def apply[F[_]](implicit materializer: RequestMaterializer[F]): RequestMaterializer[F] = materializer
}
