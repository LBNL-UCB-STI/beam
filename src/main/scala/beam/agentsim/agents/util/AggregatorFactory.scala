package beam.agentsim.agents.util

import akka.actor.{Actor, ActorRef, Props}

/**
  * Created by dserdiuk on 7/16/17.
  */
trait AggregatorFactory {
  this:  Actor  =>

  def aggregateResponsesTo(respondTo: ActorRef, requests: Map[ActorRef, List[Any]], originalSender: Option[ActorRef] = None)(resultTransformFunc: Any => Any ): Unit = {
    val aggregator = context.actorOf(Props(classOf[AggregatorActor], respondTo, Option(resultTransformFunc), originalSender))
    aggregator ! AggregatedRequest(requests)
  }

}
