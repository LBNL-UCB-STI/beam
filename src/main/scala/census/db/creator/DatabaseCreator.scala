package census.db.creator
import java.nio.file.Paths

import akka.actor.{ActorSystem, Props}
import akka.stream.ActorMaterializer
import akka.util.Timeout
import census.db.creator.config.Hardcoded
import census.db.creator.database.DbMigrationHandler
import census.db.creator.service.actors._
import census.db.creator.service.fileDownloader.FileDownloadService

/*

RUN POSTGIS DOCKER WITH FOLLOWING COMMAND:

docker run --name postgis -e POSTGRES_PASSWORD=postgres1 -e POSTGRES_DB=census -p 5432:5432 -d mdillon/postgis

 */

private[creator] object Starter extends App {

  new DbMigrationHandler(Hardcoded.config).handle()

  private implicit val system = ActorSystem()
  private implicit val materializer = ActorMaterializer()
  private implicit val executionContext = system.dispatcher

  new java.io.File(Hardcoded.config.workingDir).mkdirs()
  new java.io.File(Paths.get(Hardcoded.config.workingDir, "zips").toString).mkdirs()
  new java.io.File(Paths.get(Hardcoded.config.workingDir, "shapes").toString).mkdirs()

  val tazWriter = system.actorOf(Props(new TazSavingActor(Hardcoded.config)))
  val shapeReader = system.actorOf(Props(new ShapeReadingActor(Hardcoded.config, tazWriter)))
  val unzipper = system.actorOf(Props(new UnzipActor(Hardcoded.config, shapeReader)))
  val downloader = system.actorOf(Props(new DownloadActor(Hardcoded.config, unzipper)))

  import akka.pattern.ask
  import scala.concurrent.duration._

  implicit val timeout: Timeout = 2.seconds
  val f = downloader ? ""

  for {
    files <- new FileDownloadService(Hardcoded.config).getFileNames()
  } yield {
    files.foreach(downloader ! DownloadMessage(_))
  }

}
