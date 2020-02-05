package census.db.creator.service.actors

import akka.actor.{Actor, ActorSystem}
import census.db.creator.config.Config
import census.db.creator.database.TazInfoRepoImpl
import census.db.creator.domain.TazInfo

import scala.concurrent.ExecutionContext

case class TazBatchMessage(tazBatch: Seq[TazInfo])

class TazSavingActor(config: Config)(private implicit val executionContext: ExecutionContext) extends Actor {
  private implicit val actorSystem: ActorSystem = context.system

  override def receive: Receive = {
    case TazBatchMessage(features) =>
      val repo = new TazInfoRepoImpl(config)
      repo.save(features)
      repo.close()
  }

}
