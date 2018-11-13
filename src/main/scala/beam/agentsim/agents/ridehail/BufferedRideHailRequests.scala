package beam.agentsim.agents.ridehail

import akka.actor.ActorRef

import scala.collection.mutable

class BufferedRideHailRequests(
  val scheduler: ActorRef
) {
  private val batchedAllocationRequests = mutable.Set[RideHailRequest]()

  def add(req: RideHailRequest): Unit = {
    batchedAllocationRequests.add(req)
  }
}
