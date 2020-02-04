package census.db.creator.service.fileDownloader
import java.io.{File, FileOutputStream}
import java.nio.file.Paths

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import akka.util.ByteString
import census.db.creator.config.Config

import scala.concurrent.Future

class FileDownloadService(val config: Config) {
  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()
  // needed for the future flatMap/onComplete in the end
  private implicit val executionContext = system.dispatcher

  def downloadZipFiles(): Future[Seq[String]] = {
    for {
      filesToDownloads <- getFileNames()
      downloadedFiles  <- Future.traverse(filesToDownloads)(downloadFile)
      unzippedFiles    <- Future.traverse(downloadedFiles)(unzipFile)
    } yield {
      unzippedFiles
    }
  }

  private def downloadFile(url: String): Future[String] = {
    val fileName = url.split("/").last
    val downloadedZipFile = config.workingDir
    val downloadedFilesDir = "zips"
    val resultFileName = Paths.get(downloadedZipFile, downloadedFilesDir, fileName).toString
    val writer = new FileOutputStream(new File(resultFileName))
    for {
      zipFileStream <- Http().singleRequest(HttpRequest(uri = url))
      _ <- zipFileStream.entity.dataBytes.runForeach { x =>
        writer.write(x.asByteBuffer.array())
        Done
      }
    } yield {
      writer.flush()
      println(s"Downloaded $resultFileName")
      resultFileName
    }
  }

  private def unzipFile(path: String): Future[String] = {
    Future.successful(path)
  }

  private def getFileNames() = {
    for {
      page <- Http().singleRequest(HttpRequest(uri = config.censusUrl))
      html <- page.entity.dataBytes.runFold(ByteString(""))(_ ++ _)
    } yield {
      val pattern = "tl_\\d{4}_\\d{2}_tract\\.zip".r
      pattern.findAllIn(html.utf8String).toSeq
    }
  }
}
