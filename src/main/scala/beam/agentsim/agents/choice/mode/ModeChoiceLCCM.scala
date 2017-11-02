package beam.agentsim.agents.choice.mode

import java.util
import java.util.Random

import beam.agentsim.agents.choice.logit.LatentClassChoiceModel
import beam.agentsim.agents.choice.logit.LatentClassChoiceModel.{Mandatory, Nonmandatory, TourType}
import beam.agentsim.agents.choice.logit.MulitnomialLogit
import beam.agentsim.agents.choice.mode.ModeChoiceLCCM.ModeChoiceData
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator.AttributesOfIndividual
import beam.agentsim.agents.modalBehaviors.ModeChoiceCalculator
import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode.{BIKE, CAR, RIDEHAIL, TRANSIT, WALK}
import beam.router.RoutingModel.EmbodiedBeamTrip
import beam.sim.BeamServices

import scala.collection.JavaConverters._

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
class ModeChoiceLCCM(val beamServices: BeamServices, val lccm: LatentClassChoiceModel) extends ModeChoiceCalculator {
  var expectedMaximumUtility: Double = Double.NaN
  var classMembershipDistribution: Map[String, Double] = Map()

  override def apply(alternatives: Vector[EmbodiedBeamTrip], attributesOfIndividual: Option[AttributesOfIndividual]): Option[EmbodiedBeamTrip] = {
    this(alternatives,attributesOfIndividual,Mandatory)
  }
  def apply(alternatives: Vector[EmbodiedBeamTrip], attributesOfIndividual: Option[AttributesOfIndividual], tourType: TourType): Option[EmbodiedBeamTrip] = {
    alternatives.isEmpty match {
      case true =>
        None
      case false =>

        /*
         * First calculate values needed as input to choice models
         */
        val transitFareDefaults: Vector[BigDecimal] = TransitFareDefaults.estimateTransitFares(alternatives)
        val gasolineCostDefaults: Vector[BigDecimal] = DrivingCostDefaults.estimateDrivingCost(alternatives, beamServices)
        val bridgeTollsDefaults: Vector[BigDecimal] = BridgeTollDefaults.estimateBrdigeFares(alternatives, beamServices)

        if (bridgeTollsDefaults.map(_.toDouble).sum > 0) {
          val i = 0
        }

        val modeChoiceAlternatives: Vector[ModeChoiceData] = alternatives.zipWithIndex.map { altAndIdx =>
          val totalCost = altAndIdx._1.tripClassifier match {
            case TRANSIT =>
              (altAndIdx._1.costEstimate + transitFareDefaults(altAndIdx._2))*beamServices.beamConfig.beam.agentsim.tuning.transitPrice + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)
            case RIDEHAIL =>
              altAndIdx._1.costEstimate*beamServices.beamConfig.beam.agentsim.tuning.rideHailPrice + bridgeTollsDefaults(altAndIdx._2)*beamServices.beamConfig.beam.agentsim.tuning.tollPrice
            case CAR =>
              altAndIdx._1.costEstimate + gasolineCostDefaults(altAndIdx._2) + bridgeTollsDefaults(altAndIdx._2)*beamServices.beamConfig.beam.agentsim.tuning.tollPrice
            case _ =>
              altAndIdx._1.costEstimate
          }
          //TODO verify wait time is correct, look at transit and ride_hail in particular
          val walkTime = altAndIdx._1.legs.filter(_.beamLeg.mode == WALK).map(_.beamLeg.duration).sum
          val bikeTime = altAndIdx._1.legs.filter(_.beamLeg.mode == BIKE).map(_.beamLeg.duration).sum
          val vehicleTime = altAndIdx._1.legs.filter(_.beamLeg.mode != WALK).filter(_.beamLeg.mode != BIKE).map(_.beamLeg.duration).sum
          val waitTime = altAndIdx._1.totalTravelTime - walkTime - vehicleTime
          ModeChoiceData(altAndIdx._1.tripClassifier, tourType,vehicleTime, walkTime, waitTime, bikeTime, totalCost.toDouble, altAndIdx._2)
        } ++ ModeChoiceLCCM.defaultAlternatives(tourType)

        val groupedByMode = modeChoiceAlternatives.sortBy(_.mode.value).groupBy(_.mode)
        val bestInGroup = groupedByMode.map { case (mode, alts) =>
          // Which dominates at $18/hr for total time
          alts.map { alt => ( (alt.vehicleTime + alt.walkTime + alt.waitTime + alt.bikeTime) / 3600 * 18 + alt.cost, alt) }.sortBy(_._1).head._2
        }

        /*
         * Fill out the input data structures required by the MNL models
         */
        val modeChoiceInputData: util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]] = new util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]]()
        val classMembershipInputData: util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]] = new util.LinkedHashMap[java.lang.String, util.LinkedHashMap[java.lang.String, java.lang.Double]]()

        bestInGroup.foreach { alt =>
          val altData: util.LinkedHashMap[java.lang.String, java.lang.Double] = new util.LinkedHashMap[java.lang.String, java.lang.Double]()
          altData.put("cost", alt.cost.toDouble)
          altData.put("walkTime", alt.walkTime)
          altData.put("bikeTime", alt.bikeTime)
          altData.put("waitTime", alt.waitTime)
          altData.put("vehicleTime", alt.vehicleTime)
          modeChoiceInputData.put(alt.mode.value, altData)
        }

        val altData: util.LinkedHashMap[java.lang.String, java.lang.Double] = new util.LinkedHashMap[java.lang.String, java.lang.Double]()
        attributesOfIndividual match {
          case Some(theAttributes) =>
            altData.put("income", theAttributes.householdIncome)
            altData.put("householdSize", theAttributes.householdSize.toDouble)
            altData.put("male", if (theAttributes.isMale) {
              1.0
            } else {
              0.0
            })
            altData.put("numCars", theAttributes.numCars.toDouble)
            altData.put("numBikes", theAttributes.numBikes.toDouble)
          case None =>
        }

        lccm.classMembershipModels.head._2.getAlternativeNames.asScala.foreach{ theClassName =>
          lccm.modeChoiceModels(tourType)(theClassName).evaluateProbabilities(modeChoiceInputData)
          val modeChoiceExpectedMaxUtility = lccm.modeChoiceModels(tourType)(theClassName).getExpectedMaximumUtility
          altData.put("surplus", modeChoiceExpectedMaxUtility)
          classMembershipInputData.put(theClassName, altData)
        }

        /*
         * Evaluate and sample from classmembership, then sample from corresponding mode choice model
         */
        val probDistrib = lccm.classMembershipModels(tourType).evaluateProbabilities(classMembershipInputData)
        probDistrib.getProbabilityDensityMap.asScala.foreach{ case (className, prob) =>
          classMembershipDistribution = classMembershipDistribution + (className -> prob)
        }
        val chosenClass = lccm.classMembershipModels(tourType).makeRandomChoice(classMembershipInputData,new Random())
        val chosenMode = lccm.modeChoiceModels(tourType)(chosenClass).makeRandomChoice(modeChoiceInputData, new Random())

        expectedMaximumUtility = lccm.modeChoiceModels(tourType)(chosenClass).getExpectedMaximumUtility
        lccm.modeChoiceModels(tourType)(chosenClass).clear()
        lccm.classMembershipModels(tourType).clear()

        val chosenAlt = bestInGroup.filter(_.mode.value.equalsIgnoreCase(chosenMode))

        chosenAlt.isEmpty match {
          case true =>
            None
          case false if chosenAlt.head.index == -1 || chosenAlt.head.index >= alternatives.size =>
            None
          case false =>
            Some(alternatives(chosenAlt.head.index))
        }
    }
  }

  override def clone(): ModeChoiceCalculator = {
    val lccmClone: LatentClassChoiceModel = lccm.clone().asInstanceOf[LatentClassChoiceModel]
    new ModeChoiceLCCM(beamServices,lccmClone)
  }

}
object ModeChoiceLCCM{
  def apply(beamServices: BeamServices): ModeChoiceLCCM ={
    val lccm = new LatentClassChoiceModel(beamServices)
    new ModeChoiceLCCM(beamServices,lccm)
  }
  case class ModeChoiceData(mode: BeamMode,tourType: TourType, vehicleTime: Double, walkTime: Double, waitTime: Double, bikeTime: Double, cost: Double, index: Int = -1)
  case class ClassMembershipData(tourType: TourType, surplus: Double, income: Double, householdSize: Double, isMale: Double, numCars: Double, numBikes: Double)

  val defaultAlternatives : Map[TourType, Vector[ModeChoiceData]] = Map(
    (Mandatory -> Vector(
      ModeChoiceData(BeamMode.WALK,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.CAR,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.WALK_TRANSIT,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.DRIVE_TRANSIT,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.BIKE,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.RIDEHAIL,Mandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity)
    )),
    (Nonmandatory -> Vector(
      ModeChoiceData(BeamMode.WALK,Nonmandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.CAR,Nonmandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.WALK_TRANSIT,Nonmandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.DRIVE_TRANSIT,Nonmandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.BIKE,Nonmandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity),
      ModeChoiceData(BeamMode.RIDEHAIL,Nonmandatory,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity,Double.PositiveInfinity)
    ))
  )
}
