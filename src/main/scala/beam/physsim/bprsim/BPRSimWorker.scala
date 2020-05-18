package beam.physsim.bprsim

import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.{Id, Scenario}

import scala.annotation.tailrec
import scala.collection.JavaConverters._

/**
  *
  * @author Dmitry Openkov
  */
private[bprsim] class BPRSimWorker(
  scenario: Scenario,
  config: BPRSimConfig,
  val myLinks: Set[Id[Link]],
  val eventCollection: ConcurrentSortedCollection[Event]
) {
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
        eventCollection ++= events
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
