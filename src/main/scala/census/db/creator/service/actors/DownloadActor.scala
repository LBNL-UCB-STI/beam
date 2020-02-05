package census.db.creator.service.actors
import java.io.File
import java.nio.file.Paths

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import census.db.creator.config.Config

import scala.concurrent.ExecutionContext
import scala.util.Failure

case class DownloadMessage(url: String)

class DownloadActor(config: Config, unzipActor: ActorRef)(
  private implicit val executionContext: ExecutionContext,
  private implicit val materializer: Materializer,
) extends Actor {
  private implicit val actorSystem: ActorSystem = context.system

  override def receive: Receive = {
    case DownloadMessage(url) =>
      val fileName = url.split("/").last
      val downloadedFilesDir = "zips"
      val resultFileName = Paths.get(config.workingDir, downloadedFilesDir, fileName)
      new File(resultFileName.toString).delete()
      (for {
        zipFileStream <- Http().singleRequest(HttpRequest(uri = url))
        _             <- zipFileStream.entity.withoutSizeLimit().dataBytes.runWith(FileIO.toPath(resultFileName))
      } yield {
        println(s"Downloaded $fileName")
        unzipActor ! UnzipMessage(resultFileName.toString)
      }).andThen {
        case Failure(exception) =>
          exception.printStackTrace()
          throw exception
      }
  }

}
