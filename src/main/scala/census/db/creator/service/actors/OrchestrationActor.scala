package census.db.creator.service.actors
import akka.actor.{Actor, ActorRef}

case class TotalTaskMessage(count: Int)
case object TaskFinished
case object IsFinished

class OrchestrationActor extends Actor {
  private var tasksLeft = -1

  private var finishedListener: ActorRef = _

  override def receive: Receive = {
    case TotalTaskMessage(count) => tasksLeft = count
    case TaskFinished =>
      tasksLeft = tasksLeft - 1

      if (tasksLeft == 0) finishedListener ! true

      println(s"Tasks left $tasksLeft")

    case IsFinished => finishedListener = sender()
  }
}
