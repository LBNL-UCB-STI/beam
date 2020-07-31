package beam.agentsim.agents.choice.mode

import java.util.Random

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{Mandatory, TourType}
import beam.agentsim.agents.choice.logit.MultinomialLogit.MNLSample
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.mode.ModeChoiceLCCM.ModeChoiceData
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, DRIVE_TRANSIT, RIDE_HAIL, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.model.EmbodiedBeamTrip
import beam.sim.config.BeamConfig
import beam.sim.{BeamServices, MapStringDouble}
import beam.sim.population.AttributesOfIndividual
import org.matsim.api.core.v01.population.Activity
import org.matsim.api.core.v01.population.Person

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer

/**
  * ModeChoiceLCCM
  *
  * Data used by mode choice model:
  * --vehicleTime
  * --walkTime
  * --bikeTime
  * --waitTime
  * --cost
  *
  * Data used by class membership model:
  * --surplus
  * --income
  * --householdSize
  * --male
  * --numCars
  * --numBikes
  *
  * The LCCM models are structured to differentiate between Mandatory and Nonmandatory trips, but for this preliminary
  * implementation only the Mandatory models is used...
  * TODO pass TourType so correct model can be applied
  */
class ModeChoiceLCCM(
  val beamServices: BeamServices,
  val lccm: LatentClassChoiceModel
) extends ModeChoiceCalculator {

  override lazy val beamConfig: BeamConfig = beamServices.beamConfig

  var expectedMaximumUtility: Double = Double.NaN
  var classMembershipDistribution: Map[String, Double] = Map()

  override def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity],
    person: Option[Person] = None
  ): Option[EmbodiedBeamTrip] = {
    choose(alternatives, attributesOfIndividual, Mandatory)
  }

  private def choose(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual,
    tourType: TourType
  ): Option[EmbodiedBeamTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {
      val bestInGroup = altsToBestInGroup(alternatives, tourType)
      /*
       * Fill out the input data structures required by the MNL models
       */
      val modeChoiceInputData = bestInGroup.map { alt =>
        val theParams = Map(
          "cost" -> alt.cost,
          "time" -> (alt.walkTime + alt.bikeTime + alt.vehicleTime + alt.waitTime)
        )
        (alt.mode, theParams)
      }.toMap

      val attribIndivData: Map[String, Map[String, Double]] = {
        val theParams: Map[String, Double] = Map(
          "income"        -> attributesOfIndividual.householdAttributes.householdIncome,
          "householdSize" -> attributesOfIndividual.householdAttributes.householdSize,
          "male" -> (if (attributesOfIndividual.isMale) {
                       1.0
                     } else {
                       0.0
                     }),
          "numCars"  -> attributesOfIndividual.householdAttributes.numCars,
          "numBikes" -> attributesOfIndividual.householdAttributes.numBikes
        )
        Map("dummy" -> theParams)
      }

      val classMembershipInputData =
        lccm.classMembershipModelMaps.head._2.keySet.map { theClassName =>
          val modeChoiceExpectedMaxUtility = lccm
            .modeChoiceModels(tourType)(theClassName)
            ._2
            .getExpectedMaximumUtility(modeChoiceInputData)
          val surplusAttrib: Map[String, Double] =
            Map("surplus" -> modeChoiceExpectedMaxUtility.getOrElse(0))
          (theClassName, attribIndivData.head._2 ++ surplusAttrib)
        }.toMap

      /*
       * Evaluate and sample from classmembership, then sample from corresponding mode choice model
       */

      val chosenClassOpt = lccm
        .classMembershipModels(tourType)
        .sampleAlternative(classMembershipInputData, random)

      chosenClassOpt match {
        case None =>
          throw new IllegalArgumentException(
            "Was unable to sample from modality styles, check attributes of alternatives"
          )
        case Some(chosenClass) =>
          val chosenModeOpt = lccm
            .modeChoiceModels(tourType)(chosenClass.alternativeType)
            ._2
            .sampleAlternative(modeChoiceInputData, new Random())
          expectedMaximumUtility = lccm
            .modeChoiceModels(tourType)(chosenClass.alternativeType)
            ._2
            .getExpectedMaximumUtility(modeChoiceInputData)
            .getOrElse(0)

          chosenModeOpt match {
            case Some(chosenMode) =>
              val chosenAlt =
                bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode.alternativeType.value))
              if (chosenAlt.isEmpty) {
                None
              } else {
                Some(alternatives(chosenAlt.head.index))
              }
            case None =>
              None
          }
      }
    }
  }

  def utilityOf(
    mode: BeamMode,
    cost: Double,
    time: Double,
    numTransfers: Int = 0,
    transitOccupancyLevel: Double
  ): Double =
    0.0

  def sampleMode(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    conditionedOnModalityStyle: String,
    tourType: TourType
  ): Option[MNLSample[BeamMode]] = {
    val bestInGroup = altsToBestInGroup(alternatives, tourType)
    val modeChoiceInputData = bestInGroup.map { alt =>
      val theParams = Map(
        "cost" -> alt.cost,
        "time" -> (alt.walkTime + alt.bikeTime + alt.vehicleTime + alt.waitTime)
      )
      (alt.mode, theParams)
    }.toMap
    lccm
      .modeChoiceModels(tourType)(conditionedOnModalityStyle)
      ._2
      .sampleAlternative(modeChoiceInputData, new Random())
  }

  def utilityAcrossModalityStyles(
    embodiedBeamTrip: EmbodiedBeamTrip,
    tourType: TourType
  ): Map[String, Double] = {
    lccm
      .classMembershipModelMaps(tourType)
      .keySet
      .map(theStyle => (theStyle, utilityOf(embodiedBeamTrip, theStyle, tourType)))
      .toMap
  }

  def utilityOf(
    embodiedBeamTrip: EmbodiedBeamTrip,
    conditionedOnModalityStyle: String,
    tourType: TourType
  ): Double = {
    val best = altsToBestInGroup(Vector(embodiedBeamTrip), tourType).head

    utilityOf(
      best.mode,
      conditionedOnModalityStyle,
      tourType,
      best.cost,
      getGeneralizedTime(
        best.walkTime + best.waitTime + best.vehicleTime + best.bikeTime,
        Some(best.mode)
      ) * beamServices.beamConfig.beam.agentsim.agents.modalBehaviors.defaultValueOfTime
    )
  }

  def altsToBestInGroup(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    tourType: TourType
  ): Vector[ModeChoiceData] = {
    val transitFareDefaults: Seq[Double] =
      TransitFareDefaults.estimateTransitFares(alternatives)
    val modeChoiceAlternatives: Seq[ModeChoiceData] =
      alternatives.zipWithIndex.map { altAndIdx =>
        val totalCost = altAndIdx._1.tripClassifier match {
          case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
            (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.transitPrice
          case RIDE_HAIL =>
            altAndIdx._1.costEstimate * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice
          case _ =>
            altAndIdx._1.costEstimate
        }
        //TODO verify wait time is correct, look at transit and ride_hail in particular
        val walkTime = altAndIdx._1.legs.view
          .filter(_.beamLeg.mode == WALK)
          .map(_.beamLeg.duration)
          .sum
        val bikeTime = altAndIdx._1.legs.view
          .filter(_.beamLeg.mode == BIKE)
          .map(_.beamLeg.duration)
          .sum
        val vehicleTime = altAndIdx._1.legs.view
          .filter(_.beamLeg.mode != WALK)
          .filter(_.beamLeg.mode != BIKE)
          .map(_.beamLeg.duration)
          .sum
        val waitTime = altAndIdx._1.totalTravelTimeInSecs - walkTime - vehicleTime
        ModeChoiceData(
          altAndIdx._1.tripClassifier,
          tourType,
          vehicleTime,
          walkTime,
          waitTime,
          bikeTime,
          totalCost.toDouble,
          altAndIdx._2
        )
      }

    val groupedByMode: Map[BeamMode, Seq[ModeChoiceData]] =
      modeChoiceAlternatives.groupBy(_.mode)
    val bestInGroup = groupedByMode.map {
      case (_, alts) =>
        // Which dominates at $18/hr for total time
        alts
          .map { alt =>
            (
              (alt.vehicleTime + alt.walkTime + alt.waitTime + alt.bikeTime) / 3600 * 18 + alt.cost,
              alt
            )
          }
          .minBy(_._1)
          ._2
    }
    bestInGroup.toVector
  }

  def utilityOf(
    mode: BeamMode,
    conditionedOnModalityStyle: String,
    tourType: TourType,
    cost: Double,
    time: Double
  ): Double = {
    val theParams = Map("cost" -> cost, "time" -> time)
    lccm
      .modeChoiceModels(tourType)(conditionedOnModalityStyle)
      ._2
      .getUtilityOfAlternative(mode, theParams)
      .getOrElse(0)
  }

  override def utilityOf(
    alternative: EmbodiedBeamTrip,
    attributesOfIndividual: AttributesOfIndividual,
    destinationActivity: Option[Activity]
  ): Double = 0.0

  override def computeAllDayUtility(
    trips: ListBuffer[EmbodiedBeamTrip],
    person: Person,
    attributesOfIndividual: AttributesOfIndividual
  ): Double = {
    // Compute and log all-day score w.r.t. all modality styles
    // One of them has many suspicious-looking 0.0 values. Probably something which
    // should be minus infinity or exception instead.
    val vectorOfUtilities = List("class1", "class2", "class3", "class4", "class5", "class6")
      .map { style =>
        style -> beamServices.modeChoiceCalculatorFactory(
          attributesOfIndividual.copy(modalityStyle = Some(style))
        )
      }
      .toMap
      .mapValues(
        modeChoiceCalculatorForStyle =>
          trips.map(trip => modeChoiceCalculatorForStyle.utilityOf(trip, attributesOfIndividual, None)).sum
      )
      .toArray
      .toMap // to force computation DO NOT TOUCH IT, because here is call-by-name and it's lazy which will hold a lot of memory !!! :)

    person.getSelectedPlan.getAttributes
      .putAttribute("scores", MapStringDouble(vectorOfUtilities))

    val logsum = Option(
      math.log(
        person.getPlans.asScala.view
          .map(
            plan =>
              plan.getAttributes
                .getAttribute("scores")
                .asInstanceOf[MapStringDouble]
                .data(attributesOfIndividual.modalityStyle.get)
          )
          .map(score => math.exp(score))
          .sum
      )
    ).filterNot(x => x < -100D).getOrElse(-100D)

    // Score of being in class given this outcome
    lccm
      .classMembershipModels(Mandatory)
      .getUtilityOfAlternative(
        attributesOfIndividual.modalityStyle.get,
        Map(
          "income"        -> attributesOfIndividual.householdAttributes.householdIncome,
          "householdSize" -> attributesOfIndividual.householdAttributes.householdSize.toDouble,
          "male" -> (if (attributesOfIndividual.isMale) {
                       1.0
                     } else {
                       0.0
                     }),
          "numCars"  -> attributesOfIndividual.householdAttributes.numCars.toDouble,
          "numBikes" -> attributesOfIndividual.householdAttributes.numBikes.toDouble,
          "surplus"  -> logsum // not the logsum-thing (yet), but the conditional utility of this actual plan given the class
        )
      )
      .getOrElse(0)
  }

}

object ModeChoiceLCCM {

  case class ModeChoiceData(
    mode: BeamMode,
    tourType: TourType,
    vehicleTime: Double,
    walkTime: Double,
    waitTime: Double,
    bikeTime: Double,
    cost: Double,
    index: Int = -1
  )

  case class ClassMembershipData(
    tourType: TourType,
    surplus: Double,
    income: Double,
    householdSize: Double,
    isMale: Double,
    numCars: Double,
    numBikes: Double
  )

}
