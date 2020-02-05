package census.db.creator.service.actors
import java.io.File
import java.nio.file.Paths
import java.util.concurrent.Semaphore

import akka.actor.{Actor, ActorRef, ActorSystem}
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.Materializer
import akka.stream.scaladsl.FileIO
import census.db.creator.config.Config

import scala.concurrent.{Await, ExecutionContext}
import scala.util.Failure

case class DownloadMessage(url: String)

class DownloadActor(config: Config, unzipActor: ActorRef)(
  private implicit val executionContext: ExecutionContext,
  private implicit val materializer: Materializer,
) extends Actor {
  private implicit val actorSystem: ActorSystem = context.system

  //TODO limit downloading files count in some other way
  private val semaphore = new Semaphore(20)

  override def receive: Receive = {
    case DownloadMessage(url) =>
      val fileName = url.split("/").last
      val downloadedFilesDir = "zips"
      val resultFileName = Paths.get(config.workingDir, downloadedFilesDir, fileName)
      new File(resultFileName.toString).delete()

      semaphore.acquire()
      val future = (for {
        zipFileStream <- Http().singleRequest(HttpRequest(uri = url))
        _             <- zipFileStream.entity.withoutSizeLimit().dataBytes.runWith(FileIO.toPath(resultFileName))
      } yield {
        semaphore.release()
        println(s"Downloaded $fileName")
        unzipActor ! UnzipMessage(resultFileName.toString)
      }).andThen {
        case Failure(exception) =>
          exception.printStackTrace()
          throw exception
      }

      import scala.concurrent.duration._
      Await.result(future, 10.minutes)
  }

}
