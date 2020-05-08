package beam.physsim.bprsim

import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.population.routes.NetworkRoute
import org.slf4j.LoggerFactory

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable

/**
  *
  * @author Dmitry Openkov
  */
class BPRSimulation(scenario: Scenario, config: BPRSimConfig, events: EventsManager) extends Mobsim {
  private val queue = mutable.PriorityQueue.empty[SimEvent](Ordering.by((_: SimEvent).time).reverse)
  private val params = BPRSimParams(config, new VolumeCalculator)

  override def run(): Unit = {
    val persons = scenario.getPopulation.getPersons.values().asScala
    persons
      .map(startingEvent)
      .flatMap(_.iterator)
      .foreach(queue.enqueue(_))

    processEvents()
    println("unfinished = " + params.volumeCalculator.linkToVolume.filter(_._2 > 0))
  }

  @tailrec
  private def processEvents(): Unit = {
    if (queue.nonEmpty) {
      val simulationEvent = queue.dequeue()
      if (simulationEvent.time < config.simEndTime) {
        simulationEvent.execute(events, scenario, params).foreach(queue += _)
        processEvents()
      }
    }
  }

  private def startingEvent(person: Person): Option[StartLegSimEvent] = {
    /*
		 * return at this point, if we are just testing using a dummy
		 * person/plan (to avoid null pointer exception)
		 */
    val plan = person.getSelectedPlan
    if (plan == null || plan.getPlanElements.size() <= 1)
      None
    else {
      val actsLegs = plan.getPlanElements

      // actsLegs(0) is the first activity, actsLegs(1) is the first leg
      val firstAct = actsLegs.get(0).asInstanceOf[Activity]
      // an agent starts the first leg at the end_time of the fist act
      val departureTime = firstAct.getEndTime

      // schedule start leg message
      Some(new StartLegSimEvent(departureTime, person, 1))
    }
  }
}

object BPRSimulation {
  private[bprsim] val logger = LoggerFactory.getLogger(getClass.getName)

  def getNextLinkId(leg: Leg, link: Link): Either[String, Id[Link]] = {
    def nextLinkIdByIndex(route: NetworkRoute, ind: Int): Id[Link] =
      if (ind + 1 < route.getLinkIds.size()) route.getLinkIds.get(ind + 1)
      else route.getEndLinkId

    leg.getRoute match {
      case route: NetworkRoute =>
        if (route.getStartLinkId == link.getId) Right(nextLinkIdByIndex(route, -1))
        else {
          val ind = route.getLinkIds.indexOf(link.getId)
          if (ind < 0) Left("link not found or is the last link of the route")
          else Right(nextLinkIdByIndex(route, ind))
        }
      case _ => Left("Not a network route")
    }
  }
}
