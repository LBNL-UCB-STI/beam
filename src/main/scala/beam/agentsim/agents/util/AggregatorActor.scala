package beam.agentsim.agents.util

import akka.actor.{Actor, ActorLogging, ActorRef}
import akka.contrib.pattern.Aggregator

import scala.collection.mutable


/**
  * Expects target actors are going to response to sender actor
  *
  * @author dserdiuk 7/16/17.
  */
case class BadCommand(response: Any)
case object TimedOut


sealed trait AggregationResult

case class MultipleAggregationResult(responses : Map[ActorRef, List[Any]])extends AggregationResult

case class SingleActorAggregationResult(responses: List[Any]) extends AggregationResult {
  def mapListTo[T] = responses.asInstanceOf[List[T]]
}

case class AggregatedRequest(requests: Map[ActorRef, List[Any]])
//TODO: restrict request value to Identifiable to maintain response order
//TODO: make expected response generic or pass type(s)
//TODO: add fallback/timeout  logic
/**
  * Useage:  create aggregator actor with appropriate targetActor/message map and responseTo actor, send AggregatedRequest to aggregator actor
  * @param responseTo
  * @param transform function will be applied to aggregated response before sending in to responseTo actor
  */
class AggregatorActor(responseTo: ActorRef, transform: Option[PartialFunction[Any, Any]] = None, senderRef: Option[ActorRef] = None) extends Actor with Aggregator with ActorLogging  {


  private var requests: Map[ActorRef, List[Any]] = null
  private val responses = mutable.Map[ActorRef,  List[Any]]()

  expectOnce {
    case AggregatedRequest(theRequests) =>
      requests = theRequests
      if (requests.nonEmpty) {
        for ((targetActor, messages) <- requests) {
          for ( message <- messages) {
            targetActor ! message
          }
        }
      }
  }
  expect {
    case response: Any =>
      if (requests != null) {
        if (requests.contains(sender())) {
          log.debug(s"Got response from ${sender()} ")
          val values = responses.get(sender()).map(values => values :+ response).getOrElse(List(response))
          responses.put(sender(), values)
          respondIfDone()
        }
      }
  }

  private def respondIfDone() = {
    if (requests.size == responses.size && isDone) {
      val result =  if (responses.size == 1) {
        SingleActorAggregationResult(responses.head._2)
      } else {
        MultipleAggregationResult(responses.toMap)
      }
      transform match {
        case Some(transformFunc) if transformFunc.isDefinedAt(result) =>
          val response = transformFunc(result)
          responseWithSender(response)
        case _ =>
          responseWithSender(result)
      }
      log.debug(s"Finished aggregation request from ${sender()} ")
      context stop self
    }
  }

  private def responseWithSender(response: Any) = {
    if (senderRef.isDefined) {
      responseTo tell(response, senderRef.get)
    } else {
      responseTo ! response
    }
  }

  private def isDone = {
    responses.forall { case (ref, responseList) => requests.get(ref).map(_.size).getOrElse(-1) == responseList.size }
  }
}
