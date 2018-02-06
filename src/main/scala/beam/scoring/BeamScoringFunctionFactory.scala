package beam.scoring

import javax.inject.Inject

import beam.agentsim.events.ModeChoiceEvent
import beam.sim.BeamServices
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.scoring.{ScoringFunction, ScoringFunctionFactory}

class BeamScoringFunctionFactory @Inject()(beamServices: BeamServices)extends ScoringFunctionFactory {
  override def createNewScoringFunction(person: Person): ScoringFunction = {
    new ScoringFunction {

      val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory()

      override def handleEvent(event: Event): Unit = {
        event match {
          case modeChoiceEvent: ModeChoiceEvent =>
            // Here, if ModeChoiceCalculator is LCCM, I need to be able to get a vector of utilities (one for each modality style)
            // instead of just one.

            // println(modeChoiceEvent.chosenTrip, modeChoiceCalculator.utilityOf(modeChoiceEvent.chosenTrip))
          case _ =>
        }
      }

      override def addMoney(amount: Double): Unit = {}

      override def agentStuck(time: Double): Unit = {}

      override def handleLeg(leg: Leg): Unit = {}

      override def finish(): Unit = {}

      override def handleActivity(activity: Activity): Unit = {}

      override def getScore: Double = 0.0
    }
  }
}
