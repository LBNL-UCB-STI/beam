package beam.physsim.bprsim

import java.util.Comparator

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  *
  * @author Dmitry Openkov
  */
class BPRSimWorker(scenario: Scenario, config: BPRSimConfig, val myLinks: Set[Id[Link]], coordinator: Coordinator)
    extends StrictLogging {
  private val queue =
    new java.util.concurrent.PriorityBlockingQueue[SimEvent](2048, BPRSimWorker.javaSimEventComparator)
  private val toBeProcessed = mutable.PriorityQueue.empty[SimEvent](BPRSimulation.simEventOrdering)
  private val params = BPRSimParams(config, new VolumeCalculator)

  def init(tillTime: Double): Unit = {
    val persons = scenario.getPopulation.getPersons.values().asScala
    persons
      .map(person => BPRSimulation.startingEvent(person, firstAct => myLinks.contains(firstAct.getLinkId)))
      .flatMap(_.iterator)
      .foreach(se => acceptSimEvent(se, tillTime))
  }

  def startingTime: Double = {
    val persons = scenario.getPopulation.getPersons.values().asScala
    val eventTimes = persons
      .map(person => BPRSimulation.startingEvent(person, firstAct => myLinks.contains(firstAct.getLinkId)))
      .flatMap(_.iterator)
      .map(_.time)
    if (eventTimes.isEmpty) Double.MaxValue else eventTimes.min
  }

  def minTime: Double =
    toBeProcessed.headOption
      .map(_.time)
      .getOrElse(Double.MaxValue)

  def moveToQueue(tillTime: Double): Int = {
    @tailrec
    def moveToQueue(tillTime: Double, counter: Int): Int = {
      toBeProcessed.headOption match {
        case Some(se) =>
          if (se.time <= tillTime) {
            queue.put(toBeProcessed.dequeue())
            moveToQueue(tillTime, counter + 1)
          } else {
            counter
          }
        case None => counter
      }
    }

    moveToQueue(tillTime, 0)
  }

  def processQueuedEvents(workers: Map[Id[Link], BPRSimWorker], tillTime: Double): Int = {
    logger.debug(s"Messages in queue = ${queue.size()}, in toBeProcessed = ${toBeProcessed.size}")
    @tailrec
    def processQueuedEvents(workers: Map[Id[Link], BPRSimWorker], tillTime: Double, counter: Int): Int = {
      val simEvent = queue.poll()
      if (simEvent == null) {
        counter
      } else {
        simEvent.execute(scenario, params) match {
          case (events, simEvent) =>
            events.foreach(coordinator.processEvent)
            simEvent.foreach { event =>
              workers(event.linkId).acceptSimEvent(event, tillTime)
            }
        }
        processQueuedEvents(workers, tillTime: Double, counter + 1)
      }

    }
    processQueuedEvents(workers, tillTime, 0)
  }

  def acceptSimEvent(simEvent: SimEvent, tillTime: Double) = {
    if (simEvent.time <= tillTime) {
      queue.put(simEvent)
    } else if (simEvent.time < config.simEndTime) {
      toBeProcessed += simEvent
    }
  }
}

object BPRSimWorker {

  val javaSimEventComparator = Comparator.comparing[SimEvent, Double](
    (se: SimEvent) => se.time,
    new Comparator[Double] {
      override def compare(o1: Double, o2: Double) = o1.compareTo(o2)
    }
  )
}
