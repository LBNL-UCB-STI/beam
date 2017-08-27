package beam.agentsim.agents.util

import akka.actor.{Actor, ActorPath, ActorRef, Props}

/**
  * Created by dserdiuk on 7/16/17.
  */
trait AggregatorFactory {
  this:  Actor  =>

  def aggregateResponsesTo(respondTo: ActorRef, requests: Map[ActorPath, List[Any]], originalSender: Option[ActorRef] = None)(resultTransformFunc: PartialFunction[Any,Any] ): Unit = {
    val aggregator = context.actorOf(Props(classOf[AggregatorActor], respondTo, Option(PartialFunction(resultTransformFunc)), originalSender),"AggregatorActor")
    aggregator ! AggregatedRequest(requests)
  }

}
