package beam.side.route.processing.request

import beam.side.route.model.Url
import beam.side.route.processing.GHRequest
import org.http4s.client.Client
import org.http4s.{EntityDecoder, Uri}
import zio._

class GHRequestIO(httpClient: ZManaged[zio.ZEnv, Throwable, Client[({ type T[A] = RIO[zio.ZEnv, A] })#T]])(
  implicit val runtime: Runtime[_]
) extends GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] {

  import GHRequestIO._

  override def request[R](
    url: Url
  )(implicit decoder: EntityDecoder[({ type T[A] = RIO[zio.ZEnv, A] })#T, R]): RIO[zio.ZEnv, R] =
    httpClient.use { client =>
      (Task.succeed(client) &&& url.toUri).flatMap({ case (cl, uri) => cl.expect[R](uri) })
    }
}

object GHRequestIO {

  def apply(
    implicit runtime: Runtime[_],
    httpClient: ZManaged[zio.ZEnv, Throwable, Client[({ type T[A] = RIO[zio.ZEnv, A] })#T]]
  ): GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] =
    new GHRequestIO(httpClient)

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
