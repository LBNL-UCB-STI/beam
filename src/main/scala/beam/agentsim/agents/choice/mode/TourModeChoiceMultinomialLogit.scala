package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.logit.{MultinomialLogit, TourModeChoiceModel, UtilityFunctionOperation}
import beam.agentsim.agents.choice.logit.TourModeChoiceModel.{TourModeMNLConfig, TourModeParameters}
import beam.router.Modes.BeamMode
import beam.router.TourModes.BeamTourMode
import beam.router.skim.core.ODSkimmer.ODSkimmerTimeCostTransfer
import beam.sim.population.AttributesOfIndividual

import scala.collection.mutable
import scala.util.Random

class TourModeChoiceMultinomialLogit(
  val attributesOfIndividual: AttributesOfIndividual,
  val tourModeChoiceModel: TourModeChoiceModel
) {
  val rnd: Random = new scala.util.Random(System.currentTimeMillis())

  val tourModeLogit = MultinomialLogit[BeamTourMode, TourModeChoiceModel.TourModeParameters](
    Map.empty[BeamTourMode, Map[TourModeParameters, UtilityFunctionOperation]],
    tourModeChoiceModel.DefaultMNLParameters
  )

  def chooseTourMode(
    tourModeCosts: Seq[Map[BeamMode, ODSkimmerTimeCostTransfer]],
    modeLogit: MultinomialLogit[BeamMode, String],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]],
    firstAndLastTripModeToTourModeOption: Option[Map[BeamTourMode, Seq[BeamMode]]]
  ): Option[BeamTourMode] = {
    val tourUtility =
      tourExpectedMaxUtility(tourModeCosts, modeLogit, modeToTourMode, firstAndLastTripModeToTourModeOption)
    tourModeChoice(tourUtility, tourModeLogit)
  }

  def tripExpectedMaxUtility(
    tripModeCosts: Map[BeamMode, ODSkimmerTimeCostTransfer],
    modeLogit: MultinomialLogit[BeamMode, String],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]]
  ): Map[BeamTourMode, Double] = {
    modeToTourMode map { case (beamTourMode, beamModes) =>
      val modeChoice = beamModes.map { beamMode =>
        val skims = tripModeCosts.getOrElse(beamMode, ODSkimmerTimeCostTransfer())
        val timeCost = attributesOfIndividual.getVOT(skims.timeInHours)
        val interceptMap = modeLogit.utilityFunctions(beamMode).flatMap(_.get("intercept")).map(_.toMap)
        val monetaryCost = skims.cost
        beamMode -> (Map("cost" -> (timeCost + monetaryCost)) ++ Map(
          "transfers" -> skims.numTransfers.toDouble
        ) ++ interceptMap.getOrElse(Map.empty[String, Double]))
      }.toMap
      beamTourMode -> modeLogit.getExpectedMaximumUtility(modeChoice).getOrElse(Double.NegativeInfinity)
    }
  }

  def tourExpectedMaxUtility(
    tourModeCosts: Seq[Map[BeamMode, ODSkimmerTimeCostTransfer]],
    modeLogit: MultinomialLogit[BeamMode, String],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]],
    firstAndLastTripModeToTourModeOption: Option[Map[BeamTourMode, Seq[BeamMode]]] = None
  ): Map[BeamTourMode, Double] = {
    val tourModeToExpectedUtility = mutable.Map[BeamTourMode, Double]()
    tourModeCosts.zipWithIndex foreach { case (modeCosts, idx) =>
      if (idx == 0 | idx == tourModeCosts.length - 1) {
        // Allow inclusion of private vehicles in first/last trips, e.g. for DRIVE_TRANSIT
        // Default to normal modeToTourMode if the other mapping isn't defined, though
        tripExpectedMaxUtility(modeCosts, modeLogit, firstAndLastTripModeToTourModeOption.getOrElse(modeToTourMode))
          .map { case (tourMode, util) =>
            tourModeToExpectedUtility += (tourMode -> (tourModeToExpectedUtility.getOrElse(tourMode, 0.0) + util))
          }
      } else {
        tripExpectedMaxUtility(modeCosts, modeLogit, modeToTourMode).map { case (tourMode, util) =>
          tourModeToExpectedUtility += (tourMode -> (tourModeToExpectedUtility.getOrElse(tourMode, 0.0) + util))
        }
      }

    }
    tourModeToExpectedUtility.toMap
  }

  def tourModeChoice(
    tourModeUtility: Map[BeamTourMode, Double],
    tourModeLogit: MultinomialLogit[BeamTourMode, TourModeParameters]
  ): Option[BeamTourMode] = {
    val tourModeChoiceData: Map[BeamTourMode, Map[TourModeParameters, Double]] = tourModeUtility.map {
      case (tourMode, util) =>
        tourMode -> Map[TourModeParameters, Double](
          TourModeParameters.ExpectedMaxUtility -> util,
          TourModeParameters.Intercept          -> 0
        )
    }
    tourModeLogit.sampleAlternative(tourModeChoiceData, rnd) match {
      case Some(sample) => Some(sample.alternativeType)
      case None         => None
    }
  }

  def apply(
    tourModeCosts: Seq[Map[BeamMode, ODSkimmerTimeCostTransfer]],
    modeLogit: MultinomialLogit[BeamMode, String],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]],
    firstAndLastTripModeToTourModeOption: Option[Map[BeamTourMode, Seq[BeamMode]]]
  ): Option[BeamTourMode] = {
    chooseTourMode(tourModeCosts, modeLogit, modeToTourMode, firstAndLastTripModeToTourModeOption)
  }

  def apply(
    tourModeCosts: Seq[Map[BeamMode, ODSkimmerTimeCostTransfer]],
    modeLogit: MultinomialLogit[BeamMode, String],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]]
  ): Option[BeamTourMode] = {
    chooseTourMode(tourModeCosts, modeLogit, modeToTourMode, None)
  }
}
