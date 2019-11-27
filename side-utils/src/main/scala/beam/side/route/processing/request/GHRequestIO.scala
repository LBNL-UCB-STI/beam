package beam.side.route.processing.request

import akka.actor.ActorSystem
import beam.side.route.model.Url
import beam.side.route.processing.GHRequest
import cats.{effect => ce}
import org.http4s.Uri
import org.http4s.client.blaze._
import zio._

class GHRequestIO(implicit system: ActorSystem) extends GHRequest[({ type T[A] = IO[Throwable, A] })#T] {

  import GHRequestIO._

  private val httpClient = Http1Client[ce.IO]().unsafeRunSync

  private[this] def responseCallback(url: Url, callback: IO[Throwable, String] => Unit): Unit = {
    httpClient.expect[String](url.toUri)
  }

  override def request(url: Url): IO[Throwable, String] =
    IO.effectAsync[Throwable, String](callback => responseCallback(url, callback))
}

object GHRequestIO {
  implicit class UrlDecoded(url: Url) {

    def toUri: Uri = Uri.uri(url.host).withPath(url.path) =? url.query.mapValues(s => Seq(s.toString))
  }
}
