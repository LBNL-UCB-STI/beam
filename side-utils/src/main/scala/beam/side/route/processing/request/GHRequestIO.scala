package beam.side.route.processing.request

import beam.side.route.model.Url
import beam.side.route.processing.GHRequest
import org.http4s.Uri
import org.http4s.client.Client
import org.http4s.client.blaze._
import zio._
import zio.interop.catz._

class GHRequestIO(implicit val runtime: Runtime[_]) extends GHRequest[Task] {

  import GHRequestIO._

  private val httpClient: Task[Client[Task]] = Http1Client[Task]()

  private[this] def responseCallback[R: Decoder](url: Url)(callback: Task[R] => Unit) = {
    (httpClient &&& url.toUri).flatMap({ case (client, url) => client.expect[R](url)})
  }

  override def request[T: Decoder](url: Url): Task[T] =
    Task.effectAsync[T](callback => responseCallback(url)(callback))
}

object GHRequestIO {
  implicit class UrlDecoded(url: Url) {

    def toUri: Task[Uri] =
      Task
        .fromEither[Uri](Uri.fromString(url.host))
        .map(uri => uri.withPath(url.path) =? url.query.mapValues(s => Seq(s.toString)))
  }
}
