package beam.agentsim.agents.choice.mode

import java.util.Random

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{Mandatory, TourType}
import beam.agentsim.agents.choice.logit.{AlternativeAttributes, LatentClassChoiceModel}
import beam.agentsim.agents.choice.mode.ModeChoiceLCCM.ModeChoiceData
import beam.agentsim.agents.household.HouseholdActor.AttributesOfIndividual
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, DRIVE_TRANSIT, RIDE_HAIL, TRANSIT, WALK, WALK_TRANSIT}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

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
class ModeChoiceLCCM(val beamServices: BeamServices, val lccm: LatentClassChoiceModel, attributesOfIndividual: Option[AttributesOfIndividual]) extends ModeChoiceCalculator {
  var expectedMaximumUtility: Double = Double.NaN
  var classMembershipDistribution: Map[String, Double] = Map()

  override def apply(alternatives: Seq[EmbodiedBeamTrip]): Option[EmbodiedBeamTrip] = {
    choose(alternatives,attributesOfIndividual,Mandatory)
  }

  private def choose(alternatives: Seq[EmbodiedBeamTrip], attributesOfIndividual: Option[AttributesOfIndividual], tourType: TourType): Option[EmbodiedBeamTrip] = {
    if (alternatives.isEmpty) {
      None
    } else {
      val bestInGroup = altsToBestInGroup(alternatives,tourType)
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

      val attribIndivData: AlternativeAttributes = attributesOfIndividual match {
        case Some(theAttributes) =>
          val theParams = Map(
            "income"-> theAttributes.householdAttributes.householdIncome,
            "householdSize" -> theAttributes.householdAttributes.householdSize.toDouble,
            "male" -> (if (theAttributes.isMale) {
                        1.0
                      } else {
                        0.0
                      }),
            "numCars" -> theAttributes.householdAttributes.numCars.toDouble,
            "numBikes" -> theAttributes.householdAttributes.numBikes.toDouble
          )
          AlternativeAttributes("dummy", theParams)
        case None =>
          AlternativeAttributes("dummy",Map())
      }

      val classMembershipInputData = lccm.classMembershipModels.head._2.alternativeParams.keySet.map { theClassName =>
        if(theClassName.equalsIgnoreCase("class4")){
          val i = 0
        }
        val modeChoiceExpectedMaxUtility = lccm.modeChoiceModels(tourType)(theClassName).getExpectedMaximumUtility(modeChoiceInputData)
        val surplusAttrib = Map("surplus" -> modeChoiceExpectedMaxUtility)
        AlternativeAttributes(theClassName, attribIndivData.attributes ++ surplusAttrib)
      }.toVector

      /*
       * Evaluate and sample from classmembership, then sample from corresponding mode choice model
       */
      val chosenClassOpt = lccm.classMembershipModels(tourType).sampleAlternative(classMembershipInputData, new Random())
      chosenClassOpt match{
        case None =>
          throw new IllegalArgumentException("Was unable to sample from modality styles, check attributes of alternatives")
        case Some(chosenClass) =>
          val chosenModeOpt = lccm.modeChoiceModels(tourType)(chosenClass).sampleAlternative(modeChoiceInputData, new Random())
          expectedMaximumUtility = lccm.modeChoiceModels(tourType)(chosenClass).getExpectedMaximumUtility(modeChoiceInputData)

          chosenModeOpt match {
            case Some(chosenMode) =>
              val chosenAlt = bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))
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

  def utilityOf(mode: BeamMode, cost: Double, time: Double, numTransfers: Int = 0): Double = 0.0

  def altsToBestInGroup(alternatives: Seq[EmbodiedBeamTrip], tourType: TourType): Vector[ModeChoiceData] = {
    val transitFareDefaults: Seq[BigDecimal] = TransitFareDefaults.estimateTransitFares(alternatives)
    val gasolineCostDefaults: Seq[BigDecimal] = DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
    val bridgeTollsDefaults: Seq[BigDecimal] = BridgeTollDefaults.estimateBridgeFares(alternatives, beamServices)
    val modeChoiceAlternatives: Seq[ModeChoiceData] = alternatives.zipWithIndex.map { altAndIdx =>
      val totalCost = altAndIdx._1.tripClassifier match {
        case TRANSIT | WALK_TRANSIT | DRIVE_TRANSIT =>
          (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2)) * beamServices.beamConfig.beam.agentsim.tuning.transitPrice + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)
        case RIDE_HAIL =>
          altAndIdx._1.costEstimate * beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice + bridgeTollsDefaults(altAndIdx._2) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
        case CAR =>
          altAndIdx._1.costEstimate + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2) * beamServices.beamConfig.beam.agentsim.tuning.tollPrice
        case _ =>
          altAndIdx._1.costEstimate
      }
      //TODO verify wait time is correct, look at transit and ride_hail in particular
      val walkTime = altAndIdx._1.legs.filter(_.beamLeg.mode == WALK).map(_.beamLeg.duration).sum
      val bikeTime = altAndIdx._1.legs.filter(_.beamLeg.mode == BIKE).map(_.beamLeg.duration).sum
      val vehicleTime = altAndIdx._1.legs.filter(_.beamLeg.mode != WALK).filter(_.beamLeg.mode != BIKE).map(_.beamLeg.duration).sum
      val waitTime = altAndIdx._1.totalTravelTime - walkTime - vehicleTime
      ModeChoiceData(altAndIdx._1.tripClassifier, tourType, vehicleTime, walkTime, waitTime, bikeTime, totalCost.toDouble, altAndIdx._2)
    }

    val groupedByMode = modeChoiceAlternatives.sortBy(_.mode.value).groupBy(_.mode)
    val bestInGroup = groupedByMode.map { case (_, alts) =>
      // Which dominates at $18/hr for total time
      alts.map { alt => ((alt.vehicleTime + alt.walkTime + alt.waitTime + alt.bikeTime) / 3600 * 18 + alt.cost, alt) }.minBy(_._1)._2
    }
    bestInGroup.toVector
  }

  def sampleMode(alternatives: Seq[EmbodiedBeamTrip], conditionedOnModalityStyle: String, tourType: TourType): Option[String] = {
    val bestInGroup = altsToBestInGroup(alternatives,tourType)
    val modeChoiceInputData = bestInGroup.map{ alt =>
      val theParams = Map(
        "cost"-> alt.cost,
        "time" -> (alt.walkTime + alt.bikeTime + alt.vehicleTime + alt.waitTime)
      )
      AlternativeAttributes(alt.mode.value, theParams)
    }.toVector
    lccm.modeChoiceModels(tourType)(conditionedOnModalityStyle).sampleAlternative(modeChoiceInputData, new Random())
  }
  
  def utilityAcrossModalityStyles(embodiedBeamTrip: EmbodiedBeamTrip, tourType: TourType): Map[String,Double] = {
    lccm.classMembershipModels(tourType).alternativeParams.keySet.map( theStyle => (theStyle, utilityOf(embodiedBeamTrip, theStyle, tourType))).toMap
  }

  def utilityOf(embodiedBeamTrip: EmbodiedBeamTrip, conditionedOnModalityStyle: String, tourType: TourType): Double = {
    val best = altsToBestInGroup(Vector(embodiedBeamTrip), tourType).head

    utilityOf(best.mode, conditionedOnModalityStyle, tourType, best.cost, best.walkTime + best.waitTime + best.vehicleTime + best.bikeTime)
  }

  def utilityOf(mode: BeamMode, conditionedOnModalityStyle: String, tourType: TourType, cost: Double, time: Double): Double = {
    val theParams = Map(("cost"->cost.toDouble),("time"->time))
    lccm.modeChoiceModels(tourType)(conditionedOnModalityStyle).getUtilityOfAlternative(AlternativeAttributes(mode.value,theParams))
  }

  override def utilityOf(alternative: EmbodiedBeamTrip): Double = 0.0

}
object ModeChoiceLCCM{
  case class ModeChoiceData(mode: BeamMode,tourType: TourType, vehicleTime: Double, walkTime: Double, waitTime: Double, bikeTime: Double, cost: Double, index: Int = -1)
  case class ClassMembershipData(tourType: TourType, surplus: Double, income: Double, householdSize: Double, isMale: Double, numCars: Double, numBikes: Double)

}
