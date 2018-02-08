package beam.scoring

import javax.inject.Inject

import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.events.ModeChoiceEvent
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices
import org.apache.log4j.Logger
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.scoring.{ScoringFunction, ScoringFunctionFactory}

import scala.collection.mutable

class BeamScoringFunctionFactory @Inject()(beamServices: BeamServices) extends ScoringFunctionFactory {

  private val log = Logger.getLogger(classOf[BeamScoringFunctionFactory])

  override def createNewScoringFunction(person: Person): ScoringFunction = {
    new ScoringFunction {

      private var accumulatedScore = 0.0
      private var trips = mutable.ListBuffer[EmbodiedBeamTrip]()

      override def handleEvent(event: Event): Unit = {
        event match {
          case modeChoiceEvent: ModeChoiceEvent =>
            trips.append(modeChoiceEvent.chosenTrip)
          case _ =>
        }
      }

      override def addMoney(amount: Double): Unit = {}

      override def agentStuck(time: Double): Unit = {}

      override def handleLeg(leg: Leg): Unit = {}

      override def finish(): Unit = {
        val modalityStyle = Option(person.getCustomAttributes.get("modality-style")).map(_.asInstanceOf[String])
        val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(AttributesOfIndividual(person, null, null, modalityStyle, true))
        accumulatedScore = trips.map(trip => modeChoiceCalculator.utilityOf(trip)).sum

        // Compute and log all-day score w.r.t. all modality styles
        // One of them has many suspicious-looking 0.0 values. Probably something which
        // should be minus infinity or exception instead.
        log.debug(List("class1", "class2", "class3", "class4", "class5", "class6")
          .map(style => beamServices.modeChoiceCalculatorFactory(AttributesOfIndividual(person, null, null, Some(style), true)))
          .map(modeChoiceCalculatorForStyle => trips.map(trip => modeChoiceCalculatorForStyle.utilityOf(trip)).sum))
      }

      override def handleActivity(activity: Activity): Unit = {}

      override def getScore: Double = accumulatedScore
    }
  }
}
