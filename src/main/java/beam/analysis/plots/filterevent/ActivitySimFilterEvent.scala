package beam.analysis.plots.filterevent

import scala.collection.JavaConverters._

import beam.agentsim.events.{ModeChoiceEvent, ReplanningEvent}
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Activity, PlanElement}
import org.matsim.core.controler.MatsimServices

class ActivitySimFilterEvent(beamConfig: BeamConfig, matsimServices: MatsimServices) extends FilterEvent {

  override def graphNamePreSuffix: String = "_commute"

  private val isEnabled = beamConfig.beam.exchange.scenario.urbansim.activitySimEnabled

  override def shouldProcessEvent(event: Event): Boolean = {
    event match {
      case mcEvent: ModeChoiceEvent if isEnabled => isHomeOrWorkActivity(mcEvent)
      case rEvent: ReplanningEvent if isEnabled  => isHomeOrWorkActivity(rEvent)
      case _                                     => false
    }
  }

  private def isHomeOrWorkActivity(event: ModeChoiceEvent): Boolean = {
    val person = matsimServices.getScenario.getPopulation.getPersons.get(event.personId)
    val planElements = person.getSelectedPlan.getPlanElements.asScala.toIndexedSeq
    val current: Option[PlanElement] = planElements.lift(event.tourIndex - 1)
    val next: Option[PlanElement] = planElements.lift(event.tourIndex)
    isHomeOrWorkActivity(current) && isHomeOrWorkActivity(next)
  }

  private def isHomeOrWorkActivity(event: ReplanningEvent): Boolean = {
    isHomeOrWorkActivity(event.getEventType)
  }

  private def isHomeOrWorkActivity(activity: Option[PlanElement]): Boolean = {
    activity match {
      case Some(act: Activity) => isHomeOrWorkActivity(act.getType)
      case _                   => false
    }
  }

  private def isHomeOrWorkActivity(activity: String): Boolean = {
    "home".equalsIgnoreCase(activity) || "work".equalsIgnoreCase(activity)
  }
}
