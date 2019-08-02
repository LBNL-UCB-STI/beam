package beam.agentsim.agents.ridehail.surgepricing

import beam.agentsim.agents.ridehail.surgepricing.RideHailSurgePricingManager.SurgePriceBin
import beam.sim.BeamServices
import com.google.inject.Inject

object AdaptiveRideHailSurgePricingManager {}

class AdaptiveRideHailSurgePricingManager @Inject()(val beamServices: BeamServices) extends RideHailSurgePricingManager {


  // TODO:

  // when ever turning around direction, make the step half as large after a certain iteration number, which is specified

  // max price define
  // slowing down price change according to revenue change?
  // fix the KEEP_PRICE_LEVEL_FIXED_AT_ONE price levels below
  // define other strategies for this?

  override def initializeSurgePricingLevel(surgePriceBin: SurgePriceBin): SurgePriceBin = {
    val updatedSurgeLevel = if (rand.nextBoolean()) {
      surgePriceBin.currentIterationSurgePriceLevel + surgeLevelAdaptionStep
    } else {
      surgePriceBin.currentIterationSurgePriceLevel - surgeLevelAdaptionStep
    }
    surgePriceBin.copy(currentIterationSurgePriceLevel = updatedSurgeLevel)
  }


  override def updateSurgePricingLevel(surgePriceBin: SurgePriceBin): SurgePriceBin = {
    // TODO: move surge price by step in direction of positive movement
    //   iterate over all items
    val updatedPreviousSurgePriceLevel = surgePriceBin.currentIterationSurgePriceLevel
    val updatedSurgeLevel =
      if (surgePriceBin.currentIterationRevenue == surgePriceBin.previousIterationRevenue) {
        surgePriceBin.currentIterationSurgePriceLevel
      } else {
        if (surgePriceBin.currentIterationRevenue > surgePriceBin.previousIterationRevenue) {
          surgePriceBin.currentIterationSurgePriceLevel + (surgePriceBin.currentIterationSurgePriceLevel - surgePriceBin.previousIterationSurgePriceLevel)
        } else {
          surgePriceBin.currentIterationSurgePriceLevel - (surgePriceBin.currentIterationSurgePriceLevel - surgePriceBin.previousIterationSurgePriceLevel)
        }
      }
    surgePriceBin.copy(
      previousIterationSurgePriceLevel = updatedPreviousSurgePriceLevel,
      currentIterationSurgePriceLevel = Math.max(updatedSurgeLevel, minimumSurgeLevel)
    )
  }


}

