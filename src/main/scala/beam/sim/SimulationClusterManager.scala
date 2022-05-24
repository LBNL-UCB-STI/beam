package beam.sim

import akka.actor.{Actor, ActorRef, Address, Props}
import akka.cluster.Cluster
import akka.cluster.ClusterEvent.{MemberEvent, MemberUp}
import beam.sim.SimulationClusterManager.{GetWorkerNodes, WorkerNodes}
import com.typesafe.scalalogging.StrictLogging

/**
  * @author Dmitry Openkov
  */
class SimulationClusterManager(numWorkerNodes: Int) extends Actor with StrictLogging {
  require(numWorkerNodes > 0)
  private val cluster = Cluster(context.system)

  override def preStart(): Unit = {
    cluster.subscribe(self, classOf[MemberEvent])
  }

  override def postStop(): Unit = {
    cluster.unsubscribe(self)
  }

  override def receive: Receive = waitingForNodes(Seq.empty, Seq.empty)

  def waitingForNodes(nodes: Seq[Address], requesters: Seq[ActorRef]): Actor.Receive = {
    case GetWorkerNodes =>
      context.become(waitingForNodes(nodes, requesters :+ sender()))
    case MemberUp(member) if member.hasRole("sim-worker") =>
      val newNodes = nodes :+ member.address
      if (newNodes.size >= numWorkerNodes) {
        requesters.foreach(_ ! WorkerNodes(newNodes))
        context.become(clusterReady(newNodes))
      } else {
        context.become(waitingForNodes(newNodes, requesters))
      }
    case _: MemberEvent =>
  }

  def clusterReady(nodes: Seq[Address]): Actor.Receive = {
    case GetWorkerNodes =>
      sender() ! WorkerNodes(nodes)
    case _: MemberEvent =>
  }

}

object SimulationClusterManager {
  case object GetWorkerNodes
  case class WorkerNodes(nodes: Seq[Address])

  def props(numNodes: Int): Props = Props(new SimulationClusterManager(numNodes))
}
