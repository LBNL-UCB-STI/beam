package census.db.creator
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import census.db.creator.config.Hardcoded
import census.db.creator.database.DataRepoImpl
import census.db.creator.service.fileDownloader.FileDownloadService
import census.db.creator.service.shape.ShapefileRepo

import scala.concurrent.Future
import scala.util.{Failure, Success}

object Starter extends App {
  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()
  private implicit val executionContext = system.dispatcher

  new java.io.File(Hardcoded.config.workingDir).mkdirs()
  new java.io.File(Paths.get(Hardcoded.config.workingDir, "zips").toString).mkdirs()
  new java.io.File(Paths.get(Hardcoded.config.workingDir, "shapes").toString).mkdirs()

  for {
    fileFutures <- new FileDownloadService(Hardcoded.config).downloadZipFiles()
  } yield {
    val futures = fileFutures.map { shape =>
      shape
        .map(x => x -> new ShapefileRepo(x).getFeatures())
        .map {
          case (sh, features) =>
            val repo = new DataRepoImpl(Hardcoded.config)
            repo.save(features)
            repo.close()
            sh
        }
        .map(sh => println(s"processed shape $sh"))

    }
    Future
      .sequence(futures)
      .onComplete {
        case Success(_) =>
          println("Everything processed successfully")
          System.exit(0)
        case Failure(exception) =>
          exception.printStackTrace()
          System.exit(1)
      }
  }
}
