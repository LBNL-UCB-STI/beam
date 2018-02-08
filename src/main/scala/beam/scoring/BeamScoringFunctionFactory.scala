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

      val modalityStyle = Option(person.getCustomAttributes.get("modality-style")).map(_.asInstanceOf[String])
      private val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(AttributesOfIndividual(person, null, null, modalityStyle, true))
      private var accumulatedScore = 0.0
      private var trips = mutable.ListBuffer[EmbodiedBeamTrip]()

      override def handleEvent(event: Event): Unit = {
        event match {
          case modeChoiceEvent: ModeChoiceEvent =>
            // Here, if ModeChoiceCalculator is LCCM, I need to get a vector of utilities (one for each modality style)
            // instead of just one.
            val score = modeChoiceCalculator.utilityOf(modeChoiceEvent.chosenTrip)
            log.trace(person.getId, modeChoiceEvent.chosenTrip, score)
            trips.append(modeChoiceEvent.chosenTrip)
          case _ =>
        }
      }

      override def addMoney(amount: Double): Unit = {}

      override def agentStuck(time: Double): Unit = {}

      override def handleLeg(leg: Leg): Unit = {}

      override def finish(): Unit = {
        trips.foreach(trip => accumulatedScore += modeChoiceCalculator.utilityOf(trip))
      }

      override def handleActivity(activity: Activity): Unit = {}

      override def getScore: Double = accumulatedScore
    }
  }
}
