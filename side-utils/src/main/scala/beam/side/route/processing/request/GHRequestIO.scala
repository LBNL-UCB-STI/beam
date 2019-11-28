package beam.side.route.processing.request

import beam.side.route.model.{Url, Way}
import beam.side.route.processing.GHRequest
import org.http4s.{EntityDecoder, Uri}
import org.http4s.client.Client
import zio._

class GHRequestIO(httpClient: Task[Client[Task]])(implicit val runtime: Runtime[_]) extends GHRequest[Task] {

  import GHRequestIO._

  override def request[R](url: Url)(implicit decoder: EntityDecoder[Task, R]): Task[R] =
    (httpClient &&& url.toUri).flatMap({ case (client, uri) => client.expect[R](uri) })
}

object GHRequestIO {

  def apply(implicit runtime: Runtime[_], httpClient: Task[Client[Task]]): GHRequest[Task] =
    new GHRequestIO(httpClient)

  implicit class UrlDecoded(url: Url) {

    def toUri: Task[Uri] =
      Task
        .fromEither[Uri](Uri.fromString(url.host))
        .map(
          uri =>
            url.query.foldRight(uri) {
              case ((key, v: Seq[Double]), u) => u.+?(key, v)
              case ((key, v), u)              => u.+?(key, v.toString)
          }
        )
  }
}
