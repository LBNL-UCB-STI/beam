package beam.side.route.processing.request

import beam.side.route.model.Url
import beam.side.route.processing.GHRequest
import zio._

class GHRequestIO extends GHRequest[({ type T[A] = IO[Throwable, A] })#T] {

  override def request(url: Url): IO[Throwable, String] =
    IO.effectAsync[Throwable, String](callback => callback)
}
