package census.db.creator.service.actors

import akka.actor.{Actor, ActorRef, ActorSystem}
import census.db.creator.config.Config
import census.db.creator.service.shape.ShapefileRepo

import scala.concurrent.ExecutionContext

case class ShapeFileMessage(path: String)

class ShapeReadingActor(config: Config, tazSaver: ActorRef)(private implicit val executionContext: ExecutionContext)
    extends Actor {
  private implicit val actorSystem: ActorSystem = context.system

  override def receive: Receive = {
    case ShapeFileMessage(path) =>
      val repo = new ShapefileRepo(path)

      println(s"Shapefile $path read")

      tazSaver ! TazBatchMessage(repo.getFeatures())

      repo.close()
  }

}
