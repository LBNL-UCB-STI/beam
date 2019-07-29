package beam.agentsim.agents.ridehail

import beam.agentsim.agents.ridehail.RideHailSurgePricingManager.SurgePriceBin
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import com.google.inject.Inject

import scala.collection.mutable.ArrayBuffer

object AdaptiveRideHailSurgePricingManager {}

class AdaptiveRideHailSurgePricingManager @Inject()(val beamServices: BeamServices) extends RideHailSurgePricingManager {


  // TODO:

  // when ever turning around direction, make the step half as large after a certain iteration number, which is specified

  // max price define
  // slowing down price change according to revenue change?
  // fix the KEEP_PRICE_LEVEL_FIXED_AT_ONE price levels below
  // define other strategies for this?


  //Method to avoid code duplication
  private def updateForAllElements(
                                    surgePriceBins: Map[String, ArrayBuffer[SurgePriceBin]]
                                  )(updateFn: SurgePriceBin => SurgePriceBin): Unit = {
    surgePriceBins.values.foreach { binArray =>
      for (j <- binArray.indices) {
        val surgePriceBin = binArray.apply(j)
        val updatedBin = updateFn(surgePriceBin)
        binArray.update(j, updatedBin)
      }
    }
  }

  def updatePreviousIterationRevenuesAndResetCurrent(): Unit = {
    updateForAllElements(surgePriceBins) { surgePriceBin =>
      val updatedPrevIterRevenue = surgePriceBin.currentIterationRevenue
      surgePriceBin.copy(
        previousIterationRevenue = updatedPrevIterRevenue,
        currentIterationRevenue = 0
      )
    }
  }

  override def getSurgeLevel(location: Location, time: Double): Double = {
    val taz = beamServices.beamScenario.tazTreeMap.getTAZ(location.getX, location.getY)
    val timeBinIndex = getTimeBinIndex(time)
    surgePriceBins
      .get(taz.tazId.toString)
      .map { i =>
        if (timeBinIndex < i.size) {
          i(timeBinIndex).currentIterationSurgePriceLevel
        } else {
          1.0
        }
      }
      .getOrElse(throw new Exception("no surge level found"))
  }

  // this should be invoked after each iteration
  // TODO: initialize in BEAMSim and also reset there after each iteration?
  override def updateSurgePriceLevels(): Unit = {

    if (!priceAdjustmentStrategy.equalsIgnoreCase(KEEP_PRICE_LEVEL_FIXED_AT_ONE)) {
      if (isFirstIteration) {
        // TODO: can we refactor the following two blocks of code to reduce duplication?

        updateForAllElements(surgePriceBins) { surgePriceBin =>
          val updatedSurgeLevel = if (rand.nextBoolean()) {
            surgePriceBin.currentIterationSurgePriceLevel + surgeLevelAdaptionStep
          } else {
            surgePriceBin.currentIterationSurgePriceLevel - surgeLevelAdaptionStep
          }
          surgePriceBin.copy(currentIterationSurgePriceLevel = updatedSurgeLevel)
        }

        isFirstIteration = false

      } else {
        // TODO: move surge price by step in direction of positive movement
        //   iterate over all items
        updateForAllElements(surgePriceBins) { surgePriceBin =>
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
    }
    updatePreviousIterationRevenuesAndResetCurrent()
  }



}

