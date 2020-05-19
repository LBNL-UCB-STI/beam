package beam.physsim.bprsim

import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/**
  *
  * @author Dmitry Openkov
  */
private[bprsim] class BPRSimWorker(scenario: Scenario, config: BPRSimConfig, val myLinks: Set[Id[Link]]) {
  private val queue = mutable.PriorityQueue.empty[SimEvent](BPRSimulation.simEventOrdering)
  private val params = BPRSimParams(config, new VolumeCalculator)
  private val eventBuffer = ArrayBuffer.empty[Event]
  private val otherWorkerEvents = mutable.Map.empty[BPRSimWorker, ArrayBuffer[SimEvent]]

  def init(): Unit = {
    val persons = scenario.getPopulation.getPersons.values().asScala
    persons
      .map(person => BPRSimulation.startingEvent(person, firstAct => myLinks.contains(firstAct.getLinkId)))
      .flatMap(_.iterator)
      .foreach(se => acceptSimEvent(se))
  }

  def minTime: Double = {
    queue.headOption
      .map(_.time)
      .getOrElse(Double.MaxValue)
  }

  def processQueuedEvents(
    workers: Map[Id[Link], BPRSimWorker],
    tillTime: Double
  ): (Seq[Event], collection.Map[BPRSimWorker, Seq[SimEvent]]) = {
    @tailrec
    def processQueuedEvents(workers: Map[Id[Link], BPRSimWorker], tillTime: Double, counter: Int): Int = {
      val seOption = queue.headOption
      if (seOption.isEmpty || seOption.get.time > tillTime) {
        counter
      } else {
        val simEvent = queue.dequeue()
        val (events, maybeSimEvent) = simEvent.execute(scenario, params)
        eventBuffer ++= events
        maybeSimEvent
          .foreach { se =>
            if (myLinks.contains(se.linkId)) {
              acceptSimEvent(se)
            } else {
              val workerEvents = otherWorkerEvents.getOrElseUpdate(workers(se.linkId), ArrayBuffer.empty)
              workerEvents += se
            }
          }

        processQueuedEvents(workers, tillTime: Double, counter + 1)
      }
    }

    eventBuffer.clear()
    otherWorkerEvents.foreach {
      case (_, events) => events.clear()
    }
    processQueuedEvents(workers, tillTime, 0)
    (eventBuffer, otherWorkerEvents)
  }

  private def acceptSimEvent(simEvent: SimEvent): Unit = {
    if (simEvent.time <= config.simEndTime) {
      queue += simEvent
    }
  }

  def acceptEvents(workerEvents: Seq[SimEvent]): Unit = {
    workerEvents.foreach(acceptSimEvent)
    workerEvents.size
  }

}
