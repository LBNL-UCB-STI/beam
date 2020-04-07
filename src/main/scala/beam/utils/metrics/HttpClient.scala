package beam.utils.metrics

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpCharsets._
import akka.http.scaladsl.model.MediaTypes._
import akka.http.scaladsl.model._
import akka.stream.Materializer
import akka.util.ByteString

import scala.concurrent.Future

class HttpClient(implicit val system: ActorSystem, val materializer: Materializer) {

  def postString(uri: String, data: String): Future[HttpResponse] = {
    val entity = HttpEntity(`text/plain`.withCharset(`UTF-8`), data)
    Http().singleRequest(HttpRequest(HttpMethods.POST, uri = uri, entity = entity))
  }
}
