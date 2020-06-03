package beam.router.skim.urbansim

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern._

import scala.concurrent.{ExecutionContext, Future}

class WorkerActor(val masterActor: ActorRef, val r5Requester: ODR5Requester)(implicit val ec: ExecutionContext)
    extends Actor
    with ActorLogging {
  override def preStart(): Unit = {
    requestWork()
  }
  override def postStop(): Unit = {
    log.info(s"$self is stopped")
  }

  override def receive: Receive = {
    case resp: ODR5Requester.Response =>
      masterActor ! resp
      requestWork()
    case work: MasterActor.Response.Work =>
      Future {
        r5Requester.route(work.srcIndex, work.dstIndex)
      }.pipeTo(self)

    case MasterActor.Response.NoWork =>
      log.info(s"No more work from master $masterActor")
  }

  private def requestWork(): Unit = {
    masterActor ! MasterActor.Request.GiveMoreWork(self)
  }
}

object WorkerActor {
  sealed trait Request

  def props(masterActor: ActorRef, r5Requester: ODR5Requester, ec: ExecutionContext): Props = {
    Props(new WorkerActor(masterActor, r5Requester)(ec))
  }
}
