package beam.scoring

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.Mandatory
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, LatentClassChoiceModel}
import beam.agentsim.events.{LeavingParkingEvent, ModeChoiceEvent, ReplanningEvent}
import beam.router.Modes.BeamMode.RIDE_HAIL_POOLED
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamServices, MapStringDouble}
import javax.inject.Inject
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.api.core.v01.population.{Activity, Leg, Person}
import org.matsim.core.scoring.{ScoringFunction, ScoringFunctionFactory}
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

class BeamScoringFunctionFactory @Inject()(beamServices: BeamServices) extends ScoringFunctionFactory {

  private val log = LoggerFactory.getLogger(classOf[BeamScoringFunctionFactory])

  lazy val lccm = new LatentClassChoiceModel(beamServices)

  override def createNewScoringFunction(person: Person): ScoringFunction = {
    new ScoringFunction {

      private var finalScore = 0.0
      private val trips = mutable.ListBuffer[EmbodiedBeamTrip]()
      private var leavingParkingEventScore = 0.0

      override def handleEvent(event: Event): Unit = {
        event match {
          case modeChoiceEvent: ModeChoiceEvent =>
            trips.append(modeChoiceEvent.chosenTrip)
          case _: ReplanningEvent =>
            // FIXME? If this happens often, maybe we can optimize it:
            // trips is list buffer meaning removing is O(n)
            trips.remove(trips.size - 1)
          case leavingParkingEvent: LeavingParkingEvent =>
            leavingParkingEventScore += leavingParkingEvent.score
          case e: PersonArrivalEvent=>
            // Here we modify the last leg of the trip (the dummy walk leg) to have the right arrival time
            // This will therefore now accounts for dynamic delays or difference between quoted ride hail trip time and actual
            val bodyVehicleId = trips.head.legs.head.beamVehicleId
            if(trips.last.tripClassifier == RIDE_HAIL_POOLED){
              val i = 0
            }
            trips.update(trips.size-1,trips.last.copy(legs = trips.last.legs.dropRight(1) :+ EmbodiedBeamLeg.dummyWalkLegAt(e.getTime.toInt,bodyVehicleId,true)))
            if(trips.last.tripClassifier == RIDE_HAIL_POOLED){
              val i = 0
            }
          case _ =>
        }
      }

      override def addMoney(amount: Double): Unit = {}

      override def agentStuck(time: Double): Unit = {}

      override def handleLeg(leg: Leg): Unit = {}

      override def finish(): Unit = {
        val attributes =
          person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual]

        val modeChoiceCalculator = beamServices.modeChoiceCalculatorFactory(attributes)
        val scoreOfThisOutcomeGivenMyClass =
          trips.map(trip => modeChoiceCalculator.utilityOf(trip, attributes)).sum

        val scoreOfBeingInClassGivenThisOutcome =
          if (beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.modeChoiceClass
                .equals("ModeChoiceLCCM")) {
            // Compute and log all-day score w.r.t. all modality styles
            // One of them has many suspicious-looking 0.0 values. Probably something which
            // should be minus infinity or exception instead.
            val vectorOfUtilities = List("class1", "class2", "class3", "class4", "class5", "class6")
              .map { style =>
                style -> beamServices.modeChoiceCalculatorFactory(
                  attributes.copy(modalityStyle = Some(style))
                )
              }
              .toMap
              .mapValues(
                modeChoiceCalculatorForStyle =>
                  trips.map(trip => modeChoiceCalculatorForStyle.utilityOf(trip, attributes)).sum
              )
              .toArray
              .toMap // to force computation DO NOT TOUCH IT, because here is call-by-name and it's lazy which will hold a lot of memory !!! :)

            log.debug(vectorOfUtilities.toString())
            person.getSelectedPlan.getAttributes
              .putAttribute("scores", MapStringDouble(vectorOfUtilities))

            // TODO: Start writing something like a scala API for MATSim, so that uglinesses like that vv don't have to be in user code, but only in one place.

            val logsum = Option(
              math.log(
                person.getPlans.asScala.view
                  .map(
                    plan =>
                      plan.getAttributes
                        .getAttribute("scores")
                        .asInstanceOf[MapStringDouble]
                        .data(attributes.modalityStyle.get)
                  )
                  .map(score => math.exp(score))
                  .sum
              )
            ).filterNot(x => x < -100D).getOrElse(-100D)

            // Score of being in class given this outcome
            lccm
              .classMembershipModels(Mandatory)
              .getUtilityOfAlternative(
                AlternativeAttributes(
                  attributes.modalityStyle.get,
                  Map(
                    "income"        -> attributes.householdAttributes.householdIncome,
                    "householdSize" -> attributes.householdAttributes.householdSize.toDouble,
                    "male" -> (if (attributes.isMale) {
                                 1.0
                               } else {
                                 0.0
                               }),
                    "numCars"  -> attributes.householdAttributes.numCars.toDouble,
                    "numBikes" -> attributes.householdAttributes.numBikes.toDouble,
                    "surplus"  -> logsum // not the logsum-thing (yet), but the conditional utility of this actual plan given the class
                  )
                )
              )
          } else {
            person.getSelectedPlan.getAttributes
              .putAttribute("scores", MapStringDouble(Map("NA" -> Double.NaN)))
            scoreOfThisOutcomeGivenMyClass
          }

        finalScore = scoreOfBeingInClassGivenThisOutcome + leavingParkingEventScore
        finalScore = Math.max(finalScore, -100000) // keep scores no further below -100 to keep MATSim happy (doesn't like -Infinity) but knowing
        // that if changes to utility function drive the true scores below -100, this will need to be replaced with another big number.
      }

      override def handleActivity(activity: Activity): Unit = {}

      override def getScore: Double = finalScore
    }
  }
}
