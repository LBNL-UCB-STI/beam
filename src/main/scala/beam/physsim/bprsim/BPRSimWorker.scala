package beam.physsim.bprsim

import java.util.Comparator

import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer

/**
  *
  * @author Dmitry Openkov
  */
class BPRSimWorker(scenario: Scenario, config: BPRSimConfig, val myLinks: Set[Id[Link]],
                   val eventBuffer: ArrayBuffer[Event]) {
  private val queue = ConcurrentPriorityQueue.empty[SimEvent](BPRSimulation.simEventOrdering)
  private val params = BPRSimParams(config, new VolumeCalculator)

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

  def processQueuedEvents(workers: Map[Id[Link], BPRSimWorker], tillTime: Double): Int = {
    @tailrec
    def processQueuedEvents(workers: Map[Id[Link], BPRSimWorker], tillTime: Double, counter: Int): Int = {
      val seOption = queue.headOption
      if (seOption.isEmpty || seOption.get.time > tillTime) {
        counter
      } else {
        val simEvent = queue.dequeue()
        val (events, maybeSimEvent) = simEvent.execute(scenario, params)
        eventBuffer ++= events
        for {
          se <- maybeSimEvent
        } workers(se.linkId).acceptSimEvent(se)

        processQueuedEvents(workers, tillTime: Double, counter + 1)
      }
    }

    processQueuedEvents(workers, tillTime, 0)
  }

  def acceptSimEvent(simEvent: SimEvent): Unit = {
    if (simEvent.time <= config.simEndTime) {
      queue += simEvent
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
