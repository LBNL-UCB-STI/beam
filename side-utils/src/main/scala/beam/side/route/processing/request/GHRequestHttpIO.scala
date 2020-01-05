package beam.side.route.processing.request

import beam.side.route.model.Url
import beam.side.route.processing.GHRequest
import cats.effect.Resource
import org.http4s.client.Client
import org.http4s.{EntityDecoder, Uri}
import zio._
import zio.interop.catz._

class GHRequestHttpIO(
  httpClient: Resource[({ type T[A] = RIO[zio.ZEnv, A] })#T, Client[({ type T[A] = RIO[zio.ZEnv, A] })#T]]
)(
  implicit val runtime: Runtime[_]
) extends GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] {

  import GHRequestHttpIO._

  override def request[R](
    url: Url
  )(implicit decoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, R]): RIO[zio.ZEnv, R] =
    httpClient.use { clientRes =>
      (Task.succeed(clientRes) &&& url.toUri).flatMap {
        case (client, uri) => client.expect[R](uri)
      }
    }
}

object GHRequestHttpIO {

  def apply(
    implicit runtime: Runtime[_],
    httpClient: Resource[({ type T[A] = RIO[zio.ZEnv, A] })#T, Client[({ type T[A] = RIO[zio.ZEnv, A] })#T]]
  ): GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] =
    new GHRequestHttpIO(httpClient)

  implicit class UrlDecoded(url: Url) {

    def toUri: Task[Uri] =
      Task
        .fromEither[Uri](Uri.fromString(url.host))
        .map(_ / url.path)
        .map(
          uri =>
            url.query.foldRight(uri) {
              case ((key, v: Seq[(Double, Double)]), u) => u.+?(key, v.map(p => s"${p._1},${p._2}"))
              case ((key, v), u)                        => u.+?(key, v.toString)
          }
        )
  }
}
