package beam.agentsim.agents.choice.mode

import beam.router.Modes.BeamMode
import beam.router.TourModes.BeamTourMode
import beam.router.TourModes.BeamTourMode.WALK_BASED
import beam.router.skim.core.ODSkimmer.ODSkimmerTimeCostTransfer
import beam.sim.config.BeamConfigHolder
import beam.sim.population.AttributesOfIndividual

import scala.collection.mutable
import scala.util.Random

class TourModeChoiceMultinomialLogit(
  val attributesOfIndividual: AttributesOfIndividual,
  val configHolder: BeamConfigHolder
) {
  val rnd: Random = new scala.util.Random(System.currentTimeMillis())

  val (_, modeLogit) = ModeChoiceMultinomialLogit.buildModelFromConfig(configHolder)

  def chooseTourMode(
    tourModeCosts: Seq[Map[BeamMode, ODSkimmerTimeCostTransfer]],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]],
    firstAndLastTripModeToTourMode: Option[Map[BeamTourMode, Seq[BeamMode]]]
  ): BeamTourMode = {

    WALK_BASED
  }

  def tripExpectedMaxUtility(
    tripModeCosts: Map[BeamMode, ODSkimmerTimeCostTransfer],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]]
  ): Map[BeamTourMode, Double] = {
    modeToTourMode map { case (beamTourMode, beamModes) =>
      val modeChoice = beamModes.map { beamMode =>
        val skims = tripModeCosts.getOrElse(beamMode, ODSkimmerTimeCostTransfer())
        val timeCost = attributesOfIndividual.getVOT(skims.timeInHours)
        val monetaryCost = skims.cost
        beamMode -> (Map("cost" -> (timeCost + monetaryCost)) ++ Map(
          "transfers" -> skims.numTransfers.toDouble
        ))
      }.toMap
      beamTourMode -> modeLogit.getExpectedMaximumUtility(modeChoice).getOrElse(Double.NegativeInfinity)
    }
  }

  def tourExpectedMaxUtility(
    tourModeCosts: Seq[Map[BeamMode, ODSkimmerTimeCostTransfer]],
    modeToTourMode: Map[BeamTourMode, Seq[BeamMode]]
  ): Map[BeamTourMode, Double] = {
    val tourModeToExpectedUtility = mutable.Map[BeamTourMode, Double]()
    tourModeCosts foreach { modeCosts =>
      val tourModeExpectedCosts = tripExpectedMaxUtility(modeCosts, modeToTourMode)
      tourModeExpectedCosts.map { case (tourMode, util) =>
        tourModeToExpectedUtility += (tourMode -> (tourModeToExpectedUtility.getOrElse(tourMode, 0.0) + util))
      }
    }
    tourModeToExpectedUtility.toMap
  }
}
