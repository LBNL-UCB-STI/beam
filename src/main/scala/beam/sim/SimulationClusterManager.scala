package beam.sim

import akka.actor.{Actor, ActorRef, Props}
import beam.sim.SimulationClusterManager.{GetSimWorkers, SimWorker, SimWorkerFinished, SimWorkerReady, SimWorkers}
import com.typesafe.scalalogging.StrictLogging

/**
  * @author Dmitry Openkov
  */
class SimulationClusterManager(numWorkerNodes: Int) extends Actor with StrictLogging {
  require(numWorkerNodes > 0)

  override def receive: Receive = waitingForSimWorkers(Seq.empty, Seq.empty)

  def waitingForSimWorkers(workers: Seq[SimWorker], requesters: Seq[ActorRef]): Actor.Receive = {
    case GetSimWorkers =>
      context.become(waitingForSimWorkers(workers, requesters :+ sender()))
    case SimWorkerReady(workerNumber) =>
      val newWorkers = workers :+ SimWorker(workerNumber, sender())
      if (newWorkers.size >= numWorkerNodes) {
        requesters.foreach(_ ! SimWorkers(newWorkers))
        context.become(workersAreReady(newWorkers))
      } else {
        context.become(waitingForSimWorkers(newWorkers, requesters))
      }
  }

  def workersAreReady(workers: Seq[SimWorker]): Actor.Receive = {
    case GetSimWorkers =>
      sender() ! SimWorkers(workers)
    case SimWorkerFinished(_) =>
      context.become(waitingForAllFinished(1, Seq.empty))
  }

  def waitingForAllFinished(numFinished: Int, requesters: Seq[ActorRef]): Actor.Receive = {
    if (numFinished >= numWorkerNodes)
      waitingForSimWorkers(Seq.empty, requesters)
    else {
      case SimWorkerFinished(_) =>
        context.become(waitingForAllFinished(numFinished + 1, requesters))
      case GetSimWorkers =>
        context.become(waitingForAllFinished(numFinished, requesters))
    }
  }

}

object SimulationClusterManager {
  case object GetSimWorkers
  case class SimWorker(workerNumber: Int, actorRef: ActorRef)
  case class SimWorkers(workers: Seq[SimWorker])
  case class SimWorkerReady(workerNumber: Int)
  case class SimWorkerFinished(workerNumber: Int)

  def props(numNodes: Int): Props = Props(new SimulationClusterManager(numNodes))
}
