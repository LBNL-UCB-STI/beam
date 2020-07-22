package beam.physsim.bprsim

import java.{lang, util}

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Activity, Person}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup.PRIORITY_DEPARTUARE_MESSAGE

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  *
  * @author Dmitry Openkov
  */
class BPRSimulation(scenario: Scenario, config: BPRSimConfig, eventManager: EventsManager)
    extends Mobsim
    with StrictLogging {
  private val queue = mutable.PriorityQueue.empty[SimEvent](BPRSimulation.simEventOrdering)
  private val params = BPRSimParams(config, new VolumeCalculator(config.inFlowAggregationTimeWindow))

  override def run(): Unit = {
    val persons = scenario.getPopulation.getPersons.values().asScala
    val caccMap = params.config.caccSettings.map(_.isCACCVehicle).getOrElse(java.util.Collections.emptyMap())
    persons
      .map(person => BPRSimulation.startingEvent(person, caccMap, _ => true))
      .flatMap(_.iterator)
      .foreach(queue.enqueue(_))

    processQueuedEvents()
    config.caccSettings.foreach(_.roadCapacityAdjustmentFunction.printStats())
  }

  @tailrec
  private def processQueuedEvents(): Unit = {
    if (queue.nonEmpty) {
      val simulationEvent = queue.dequeue()
      simulationEvent.execute(scenario, params) match {
        case (events, simEvent) =>
          events.foreach(eventManager.processEvent)
          for {
            e <- simEvent if e.time < config.simEndTime
          } queue += e
      }
      processQueuedEvents()
    }
  }
}

object BPRSimulation {
  implicit val eventTimeOrdering: Ordering[Event] = (x: Event, y: Event) => {
    java.lang.Double.compare(x.getTime, y.getTime)
  }

  private[bprsim] val simEventOrdering: Ordering[SimEvent] = (x: SimEvent, y: SimEvent) => {
    val c1 = java.lang.Double.compare(y.time, x.time)
    if (c1 != 0) c1 else java.lang.Integer.compare(x.priority, y.priority)
  }

  private[bprsim] def startingEvent(
    person: Person,
    caccMap: java.util.Map[String, java.lang.Boolean],
    accept: Activity => Boolean
  ): Option[StartLegSimEvent] = {
    val plan = person.getSelectedPlan
    if (plan == null || plan.getPlanElements.size() <= 1)
      None
    else {
      // actsLegs(0) is the first activity, actsLegs(1) is the first leg
      val firstAct = plan.getPlanElements.get(0).asInstanceOf[Activity]
      if (accept(firstAct)) {
        // an agent starts the first leg at the end_time of the fist act
        val departureTime = firstAct.getEndTime

        // schedule start leg message
        val isCACC = caccMap.getOrDefault(person.getId.toString, false)
        Some(new StartLegSimEvent(departureTime, PRIORITY_DEPARTUARE_MESSAGE, person, isCACC, 1))
      } else {
        None
      }
    }
  }
}
