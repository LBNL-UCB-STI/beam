package census.db.creator.service.fileDownloader
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import akka.util.ByteString
import census.db.creator.config.Config

import scala.concurrent.{ExecutionContext, Future}

private[creator] class FileDownloadService(val config: Config)(
  implicit val system: ActorSystem,
  implicit val materializer: ActorMaterializer,
  implicit val executionContext: ExecutionContext
) {

  def getFileNames(): Future[Seq[String]] = {
    for {
      page <- Http().singleRequest(HttpRequest(uri = config.censusUrl))
      html <- page.entity.dataBytes.runFold(ByteString(""))(_ ++ _)
    } yield {
      "tl_\\d{4}_\\d{2}_tract\\.zip".r
        .findAllIn(html.utf8String)
        .toSeq
        .distinct
        .map(config.censusUrl + _)
    }
  }

}
