package census.db.creator.service.actors

import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorRef, ActorSystem}
import census.db.creator.config.Config
import census.db.creator.database.PostgresTazRepo
import census.db.creator.domain.TazInfo

import scala.concurrent.ExecutionContext

case class TazBatchMessage(tazBatch: Seq[TazInfo])

class TazSavingActor(config: Config, orchestrationActor: ActorRef)(
  private implicit val executionContext: ExecutionContext
) extends Actor {
  private implicit val actorSystem: ActorSystem = context.system

  private val repo = new PostgresTazRepo(config)

  override def receive: Receive = {
    case TazBatchMessage(features) =>
      repo.save(features)

      orchestrationActor ! TaskFinished
  }

}
