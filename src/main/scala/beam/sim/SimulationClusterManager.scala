package beam.sim

import akka.actor.{Actor, ActorRef, Props}
import beam.sim.SimulationClusterManager.{GetSimWorkers, SimWorker, SimWorkerFinished, SimWorkerReady}
import com.typesafe.scalalogging.StrictLogging

/**
  * @author Dmitry Openkov
  */
class SimulationClusterManager(numWorkerNodes: Int) extends Actor with StrictLogging {
  require(numWorkerNodes > 0)

  override def receive: Receive = waitingForSimWorkers(IndexedSeq.empty, Seq.empty)

  def waitingForSimWorkers(workers: IndexedSeq[SimWorker], requesters: Seq[ActorRef]): Actor.Receive = {
    case GetSimWorkers =>
      context.become(waitingForSimWorkers(workers, requesters :+ sender()))
    case SimWorkerReady(workerNumber, simulationPart) =>
      logger.info(s"Received SimWorkerReady: $workerNumber")
      val newWorkers = (workers :+ SimWorker(workerNumber, simulationPart)).sortBy(_.workerNumber)
      if (newWorkers.size >= numWorkerNodes) {
        requesters.foreach(_ ! newWorkers)
        context.become(workersAreReady(newWorkers))
      } else {
        context.become(waitingForSimWorkers(newWorkers, requesters))
      }
  }

  def workersAreReady(workers: IndexedSeq[SimWorker]): Actor.Receive = {
    case GetSimWorkers =>
      sender() ! workers
    case SimWorkerFinished(_) =>
      context.become(waitingForAllFinished(1, Seq.empty))
  }

  def waitingForAllFinished(numFinished: Int, requesters: Seq[ActorRef]): Actor.Receive = {
    if (numFinished >= numWorkerNodes)
      waitingForSimWorkers(IndexedSeq.empty, requesters)
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
  case class SimWorker(workerNumber: Int, simulationPart: ActorRef)
  case class SimWorkerReady(workerNumber: Int, simulationPart: ActorRef)
  case class SimWorkerFinished(workerNumber: Int)

  def props(numNodes: Int): Props = Props(new SimulationClusterManager(numNodes))

  def splitIntoParts[A](xs: Iterable[A], n: Int): IndexedSeq[Iterable[A]] = {
    val (quot, rem) = (xs.size / n, xs.size % n)
    val (smaller, bigger) = xs.splitAt(xs.size - rem * (quot + 1))
    (smaller.grouped(quot) ++ bigger.grouped(quot + 1)).toIndexedSeq
  }

  def getPart[A](seq: Iterable[A], partNumber: Int, totalParts: Int): Iterable[A] = {
    splitIntoParts(seq, totalParts)(partNumber)
  }
}
