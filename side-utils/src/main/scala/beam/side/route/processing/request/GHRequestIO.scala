package beam.side.route.processing.request

import beam.side.route.model.Url
import beam.side.route.processing.GHRequest
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze._
import zio._
import zio.interop.catz._

class GHRequestIO(httpClient: Task[Client[Task]])(implicit val runtime: Runtime[_]) extends GHRequest[Task] {

  import GHRequestIO._

  //private val httpClient: Task[Client[Task]] = Http1Client[Task]()

  override def request[R: Decoder](url: Url): Task[R] =
    (httpClient &&& url.toUri).flatMap({ case (client, uri) => client.expect[R](uri) })
}

object GHRequestIO {
  implicit class UrlDecoded(url: Url) {

    def toUri: Task[Uri] =
      Task
        .fromEither[Uri](Uri.fromString(url.host))
        .map(uri => uri.withPath(url.path) =? url.query.mapValues(s => Seq(s.toString)))
  }
}
