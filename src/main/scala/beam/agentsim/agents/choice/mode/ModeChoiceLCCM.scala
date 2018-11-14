package beam.agentsim.agents.choice.mode

import java.util.Random

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{Mandatory, TourType}
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, LatentClassChoiceModel}
import beam.agentsim.agents.choice.mode.ModeChoiceLCCM.ModeChoiceData
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, RIDE_HAIL, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.model.EmbodiedBeamTrip
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual

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
  var expectedMaximumUtility: Double = Double.NaN
  var classMembershipDistribution: Map[String, Double] = Map()

  override def apply(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    attributesOfIndividual: AttributesOfIndividual
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
        AlternativeAttributes(alt.mode.value, theParams)
      }

      val attribIndivData: AlternativeAttributes = {
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
        AlternativeAttributes("dummy", theParams)
      }

      val classMembershipInputData =
        lccm.classMembershipModels.head._2.alternativeParams.keySet.map { theClassName =>
          val modeChoiceExpectedMaxUtility = lccm
            .modeChoiceModels(tourType)(theClassName)
            .getExpectedMaximumUtility(modeChoiceInputData)
          val surplusAttrib: Map[String, Double] =
            Map("surplus" -> modeChoiceExpectedMaxUtility)
          AlternativeAttributes(theClassName, attribIndivData.attributes ++ surplusAttrib)
        }.toVector

      /*
       * Evaluate and sample from classmembership, then sample from corresponding mode choice model
       */
      val chosenClassOpt = lccm
        .classMembershipModels(tourType)
        .sampleAlternative(classMembershipInputData, new Random())

      chosenClassOpt match {
        case None =>
          throw new IllegalArgumentException(
            "Was unable to sample from modality styles, check attributes of alternatives"
          )
        case Some(chosenClass) =>
          val chosenModeOpt = lccm
            .modeChoiceModels(tourType)(chosenClass)
            .sampleAlternative(modeChoiceInputData, new Random())
          expectedMaximumUtility = lccm
            .modeChoiceModels(tourType)(chosenClass)
            .getExpectedMaximumUtility(modeChoiceInputData)

          chosenModeOpt match {
            case Some(chosenMode) =>
              val chosenAlt =
                bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))
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

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double =
    0.0

  def sampleMode(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    conditionedOnModalityStyle: String,
    tourType: TourType
  ): Option[String] = {
    val bestInGroup = altsToBestInGroup(alternatives, tourType)
    val modeChoiceInputData = bestInGroup.map { alt =>
      val theParams = Map(
        "cost" -> alt.cost,
        "time" -> (alt.walkTime + alt.bikeTime + alt.vehicleTime + alt.waitTime)
      )
      AlternativeAttributes(alt.mode.value, theParams)
    }
    lccm
      .modeChoiceModels(tourType)(conditionedOnModalityStyle)
      .sampleAlternative(modeChoiceInputData, new Random())
  }

  def utilityAcrossModalityStyles(
    embodiedBeamTrip: EmbodiedBeamTrip,
    tourType: TourType
  ): Map[String, Double] = {
    lccm
      .classMembershipModels(tourType)
      .alternativeParams
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
      scaleTimeByVot(
        best.walkTime + best.waitTime + best.vehicleTime + best.bikeTime,
        Some(best.mode)
      )
    )
  }

  def altsToBestInGroup(
    alternatives: IndexedSeq[EmbodiedBeamTrip],
    tourType: TourType
  ): Vector[ModeChoiceData] = {
    val transitFareDefaults: Seq[Double] =
      TransitFareDefaults.estimateTransitFares(alternatives)
    val gasolineCostDefaults: Seq[Double] =
      DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
    val modeChoiceAlternatives: Seq[ModeChoiceData] =
      alternatives.zipWithIndex.map { altAndIdx =>
        val totalCost = altAndIdx._1.tripClassifier match {
          case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
            (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.transitPrice + gasolineCostDefaults(
              altAndIdx._2
            )
          case RIDE_HAIL =>
            altAndIdx._1.costEstimate * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice
          case CAR =>
            altAndIdx._1.costEstimate + gasolineCostDefaults(altAndIdx._2)
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
      .getUtilityOfAlternative(AlternativeAttributes(mode.value, theParams))
  }

  override def utilityOf(alternative: EmbodiedBeamTrip, attributesOfIndividual: AttributesOfIndividual): Double = 0.0

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
