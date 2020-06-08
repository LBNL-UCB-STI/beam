package beam.analysis.plots.filterevent

import scala.collection.JavaConverters._

import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent}
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Activity, PlanElement}
import org.matsim.core.controler.MatsimServices

class ActivitySimFilterEvent(matsimServices: MatsimServices) extends FilterEvent {

  override def graphNamePreSuffix: String = "_commute"

  override def shouldProcessEvent(event: Event): Boolean = {
    event match {
      case mcEvent: ModeChoiceEvent => isHomeOrWorkActivity(mcEvent)
      case rEvent: ReplanningEvent  => isHomeOrWorkActivity(rEvent)
      case _                        => false
    }
  }

  private def isHomeOrWorkActivity(event: ModeChoiceEvent): Boolean = {
    val person = matsimServices.getScenario.getPopulation.getPersons.get(event.personId)
    val plan = person.getSelectedPlan
    val planElements = person.getSelectedPlan.getPlanElements.asScala.toIndexedSeq
    val current: Option[PlanElement] = planElements.lift(event.tourIndex-1)
    val next: Option[PlanElement] = planElements.lift(event.tourIndex)
    isHomeOrWorkActivity(current) && isHomeOrWorkActivity(next)
  }

  private def isHomeOrWorkActivity(event: ReplanningEvent): Boolean = {
    isHomeOrWorkActivity(event.getEventType)
  }

  private def isHomeOrWorkActivity(activity: Option[PlanElement]): Boolean = {
    activity match {
      case Some(act: Activity) => isHomeOrWorkActivity(act.getType)
      case _ => false
    }
  }

  private def isHomeOrWorkActivity(activity: String): Boolean = {
    "home".equalsIgnoreCase(activity) || "work".equalsIgnoreCase(activity)
  }
}
