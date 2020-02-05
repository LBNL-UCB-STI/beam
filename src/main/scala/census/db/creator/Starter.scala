package census.db.creator
import java.nio.file.Paths

import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import census.db.creator.config.Hardcoded
import census.db.creator.database.{DbMigrationHandler, TazInfoRepoImpl}
import census.db.creator.service.fileDownloader.FileDownloadService
import census.db.creator.service.shape.ShapefileRepo
import com.typesafe.config.{Config, ConfigFactory}

import scala.concurrent.Future
import scala.util.{Failure, Success}

/*

RUN POSTGIS DOCKER WITH FOLLOWING COMMAND:

docker run --name postgis -e POSTGRES_PASSWORD=postgres1 -e POSTGRES_DB=census -p 5432:5432 -d mdillon/postgis

 */

private[creator] object Starter extends App {

  new DbMigrationHandler(Hardcoded.config).handle()

  val config: Config = ConfigFactory
    .parseString(
      """
        akka.http.host-connection-pool.max-open-requests = 128
        """
    )
    .withFallback(ConfigFactory.load())
    .resolve()

  private implicit val system = ActorSystem("system", config)
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
        .map { x =>
          val repo = new ShapefileRepo(x)
          val features = repo.getFeatures()
          repo.close()
          x -> features
        }
        .map {
          case (sh, features) =>
            val repo = new TazInfoRepoImpl(Hardcoded.config)
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
