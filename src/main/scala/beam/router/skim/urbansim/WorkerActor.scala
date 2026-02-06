package beam.router.skim.urbansim

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import akka.pattern._

import scala.concurrent.{ExecutionContextExecutorService, Future}
import scala.util.control.NonFatal

class WorkerActor(val masterActor: ActorRef, val r5Requester: ODRequester)(implicit
  val ec: ExecutionContextExecutorService
) extends Actor
    with ActorLogging {

  var nTotalRequests: Int = 0
  var nSuccess: Int = 0

  override def preStart(): Unit = {
    requestWork()
  }

  override def postStop(): Unit = {
    log.info(s"$self is stopped. Total number of requests: $nTotalRequests, success: $nSuccess")
  }

  override def receive: Receive = {
    case MasterActor.Response.WorkBatch(items) =>
      nTotalRequests += items.length
      Future {
        items.flatMap { case (srcIndex, dstIndex, requestTime) =>
          try {
            Some(r5Requester.route(srcIndex, dstIndex, requestTime))
          } catch {
            case NonFatal(ex) =>
              log.error(ex, s"route failed: ${ex.getMessage}")
              None
          }
        }
      }.pipeTo(self)

    case responses: Array[ODRequester.Response @unchecked] =>
      responses.foreach { resp =>
        if (resp.maybeRoutingResponse.isSuccess) nSuccess += 1
        masterActor ! resp
      }
      requestWork()

    // Keep backward compatibility with single work items
    case resp: ODRequester.Response =>
      if (resp.maybeRoutingResponse.isSuccess)
        nSuccess += 1
      masterActor ! resp
      requestWork()

    case work: MasterActor.Response.Work =>
      nTotalRequests += 1
      Future {
        try {
          r5Requester.route(work.srcIndex, work.dstIndex, work.requestTime)
        } catch {
          case NonFatal(ex) =>
            log.error(ex, s"route failed: ${ex.getMessage}")
        }
      }.pipeTo(self)

    case MasterActor.Response.NoWork =>
      log.debug(s"No more work from master $masterActor")
  }

  private def requestWork(): Unit = {
    masterActor ! MasterActor.Request.GiveMoreWork(self)
  }
}

object WorkerActor {

  def props(masterActor: ActorRef, r5Requester: ODRequester, ec: ExecutionContextExecutorService): Props = {
    Props(new WorkerActor(masterActor, r5Requester)(ec))
  }
}
