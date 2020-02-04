package census.db.creator.service.fileDownloader
import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.file.Paths
import java.util.zip.ZipInputStream

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.FileIO
import akka.util.ByteString
import census.db.creator.config.Config

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Failure

private[creator] class FileDownloadService(val config: Config)(
  implicit val system: ActorSystem,
  implicit val materializer: ActorMaterializer,
  implicit val executionContext: ExecutionContext
) {

  // needed for the future flatMap/onComplete in the end
  def downloadZipFiles(): Future[Seq[Future[String]]] = {
    for {
      filesToDownloads <- getFileNames()
    } yield {
      filesToDownloads.distinct
        .map(config.censusUrl + _)
        .map(downloadFile)
        .map(x => x.flatMap(unzipFile))
    }
  }

  private def downloadFile(url: String): Future[String] = {
    val fileName = url.split("/").last
    val downloadedFilesDir = "zips"
    val resultFileName = Paths.get(config.workingDir, downloadedFilesDir, fileName)
    new File(resultFileName.toString).delete()
    (for {
      zipFileStream <- Http().singleRequest(HttpRequest(uri = url))
      _             <- zipFileStream.entity.withoutSizeLimit().dataBytes.runWith(FileIO.toPath(resultFileName))
    } yield {
      println(s"Downloaded $resultFileName")
      resultFileName.toString
    }).andThen {
      case Failure(exception) =>
        exception.printStackTrace()
        throw exception
    }
  }

  private def unzipFile(path: String): Future[String] = {
    val fileName = path.split("/").last
    val shapefilesDir = "shapes"

    val resultDir = Paths.get(config.workingDir, shapefilesDir).toString
    var shapeFile: String = ""
    Future {
      val zis = new ZipInputStream(new FileInputStream(path))
      Stream.continually(zis.getNextEntry).takeWhile(_ != null).foreach { file =>
        val fileName = Paths.get(resultDir, file.getName).toString
        if (fileName.endsWith(".shp"))
          shapeFile = fileName
        new File(fileName).delete()
        val fout = new FileOutputStream(fileName)
        val buffer = new Array[Byte](1024)
        Stream.continually(zis.read(buffer)).takeWhile(_ != -1).foreach(fout.write(buffer, 0, _))
        zis.closeEntry()
      }
      zis.close()
      if (shapeFile.isEmpty) throw new RuntimeException(s"no shapefile! for file $path")
      shapeFile
    }
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
