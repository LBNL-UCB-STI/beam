package beam.scoring

import javax.inject.Inject

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.Mandatory
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, LatentClassChoiceModel}
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.events.ModeChoiceEvent
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.{BeamServices, MapStringDouble}
import org.apache.log4j.Logger
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.scoring.{ScoringFunction, ScoringFunctionFactory}

import scala.collection.JavaConverters._
import scala.collection.mutable

class BeamScoringFunctionFactory @Inject()(beamServices: BeamServices) extends ScoringFunctionFactory {

  private val log = Logger.getLogger(classOf[BeamScoringFunctionFactory])

  val lccm = new LatentClassChoiceModel(beamServices)

  override def createNewScoringFunction(person: Person): ScoringFunction = {
    new ScoringFunction {

      private var finalScore = 0.0
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
        val attributes = person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]

        val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(attributes)
        val scoreOfThisOutcomeGivenMyClass = trips.map(trip => modeChoiceCalculator.utilityOf(trip)).sum

        // Compute and log all-day score w.r.t. all modality styles
        // One of them has many suspicious-looking 0.0 values. Probably something which
        // should be minus infinity or exception instead.
        val vectorOfUtilities = List("class1", "class2", "class3", "class4", "class5", "class6").map{style =>
          style -> beamServices.modeChoiceCalculatorFactory(attributes.copy(modalityStyle = Some(style)))}
          .toMap
          .mapValues(modeChoiceCalculatorForStyle => trips.map(trip => modeChoiceCalculatorForStyle.utilityOf(trip)).sum)
        log.debug(vectorOfUtilities)
        person.getSelectedPlan.getAttributes.putAttribute("scores", MapStringDouble(vectorOfUtilities))

        // TODO: Start writing something like a scala API for MATSim, so that uglinesses like that vv don't have to be in user code, but only in one place.

        val logsum = math.log(person.getPlans().asScala
          .map(plan => plan.getAttributes.getAttribute("scores").asInstanceOf[MapStringDouble].data(attributes.modalityStyle.get))
          .map(score => math.exp(score))
          .sum)

        val scoreOfBeingInClassGivenThisOutcome = lccm.classMembershipModels(Mandatory).getUtilityOfAlternative(AlternativeAttributes(attributes.modalityStyle.get, Map(
          "income" -> attributes.householdAttributes.householdIncome,
          "householdSize" -> attributes.householdAttributes.householdSize.toDouble,
          "male" -> (if (attributes.isMale) {
            1.0
          } else {
            0.0
          }),
          "numCars" -> attributes.householdAttributes.numCars.toDouble,
          "numBikes" -> attributes.householdAttributes.numBikes.toDouble,
          "surplus" -> logsum   // not the logsum-thing (yet), but the conditional utility of this actual plan given the class
        )))

        finalScore = Math.max(scoreOfBeingInClassGivenThisOutcome, -100.0) // keep scores no further below -100 to keep MATSim happy (doesn't like -Infinity) but knowing
        // that if changes to utility function drive the true scores below -100, this will need to be replaced with another big number.
      }

      override def handleActivity(activity: Activity): Unit = {}

      override def getScore: Double = finalScore
    }
  }
}
