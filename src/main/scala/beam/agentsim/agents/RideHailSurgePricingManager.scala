package beam.agentsim.agents


import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import com.google.inject.Inject

import scala.collection.JavaConverters._
import scala.collection.mutable.{ArrayBuffer, ArraySeq, HashMap}
import scala.util.Random

class RideHailSurgePricingManager @Inject()(beamConfig: BeamConfig, tazTreeMap: TAZTreeMap) {

  // TODO: load following parameters directly from config (add them there)zz

  // TODO: can we allow any other class to inject taz as well, without loading multiple times? (Done)

  val timeBinSize = 60 * 60; // TODO: does throw exception for 60min, if +1 missing below
  val numberOfTimeBins = 3600 * 24 / timeBinSize
  val surgeLevelAdaptionStep = 0.1;
  val minimumSurgeLevel=0.1
  var isFirstIteration = true

  // TODO: implement all cases for these surge prices properly
  val CONTINUES_DEMAND_SUPPLY_MATCHING="CONTINUES_DEMAND_SUPPLY_MATCHING"
  val KEEP_PRICE_LEVEL_FIXED_AT_ONE="KEEP_PRICE_LEVEL_FIXED_AT_ONE"

  var priceAdjustmentStrategy=CONTINUES_DEMAND_SUPPLY_MATCHING

  //  var surgePriceBins: HashMap[String, ArraySeq[SurgePriceBin]] = new HashMap()

  var rideHailingRevenue = ArrayBuffer[Double]()

  // TODO: add system iteration revenue in class (add after each iteration), so that it can be accessed during graph generation!

  // TODO: initialize all bins (price levels and iteration revenues)!

  //Scala like code
  val surgePriceBins = tazTreeMap.tazQuadTree
    .values
    .asScala
    .map { v =>
      val array = (0 until numberOfTimeBins).foldLeft(new ArrayBuffer[SurgePriceBin]) { (arrayBuffer, _) =>
        arrayBuffer.append(SurgePriceBin(0.0, 0.0, 1.0, 1.0))
        arrayBuffer
      }
      (v.tazId.toString, array)
    }.toMap

  // this should be invoked after each iteration
  // TODO: initialize in BEAMSim and also reset there after each iteration?
  def updateSurgePriceLevels(): Unit = {
    if (isFirstIteration) {
      // TODO: can we refactor the following two blocks of code to reduce duplication?
      println()

      // TODO: seed following random to some config seed?
      val rand = Random
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
      print()
      updateForAllElements(surgePriceBins) { surgePriceBin =>
        val updatedPreviousSurgePriceLevel = surgePriceBin.currentIterationSurgePriceLevel;
        val updatedSurgeLevel = if (surgePriceBin.currentIterationRevenue == surgePriceBin.previousIterationRevenue) {
          surgePriceBin.currentIterationSurgePriceLevel
        } else {
          if (surgePriceBin.currentIterationRevenue > surgePriceBin.previousIterationRevenue) {
            surgePriceBin.currentIterationSurgePriceLevel + (surgePriceBin.currentIterationSurgePriceLevel - surgePriceBin.previousIterationSurgePriceLevel)
          } else {
            surgePriceBin.currentIterationSurgePriceLevel - (surgePriceBin.currentIterationSurgePriceLevel - surgePriceBin.previousIterationSurgePriceLevel)
          }
        }
        surgePriceBin.copy(previousIterationSurgePriceLevel = updatedPreviousSurgePriceLevel, currentIterationSurgePriceLevel = Math.max(updatedSurgeLevel,minimumSurgeLevel))
      }


    }
    updatePreviousIterationRevenuesAndResetCurrent
  }

  //Method to avoid code duplication
  private def updateForAllElements(surgePriceBins: Map[String, ArrayBuffer[SurgePriceBin]])(updateFn: SurgePriceBin => SurgePriceBin): Unit = {
    surgePriceBins.values.foreach { binArray =>
      for (j <- 0 until binArray.size) {
        val surgePriceBin = binArray.apply(j)
        val updatedBin = updateFn(surgePriceBin)
        binArray.update(j, updatedBin)
      }
    }
  }

  private def updatePreviousIterationRevenuesAndResetCurrent = {
    updateForAllElements(surgePriceBins) { surgePriceBin =>
      val updatedPrevIterRevenue = surgePriceBin.currentIterationRevenue
      surgePriceBin.copy(previousIterationRevenue = updatedPrevIterRevenue, currentIterationRevenue = 0)
    }
  }

  def getSurgeLevel(location: Location, time: Double): Double = {
    if (tazTreeMap == null || priceAdjustmentStrategy.equalsIgnoreCase(KEEP_PRICE_LEVEL_FIXED_AT_ONE)) 1.0
    else {
      val taz = tazTreeMap.getTAZ(location.getX, location.getY)
      val timeBinIndex = getTimeBinIndex(time)
      surgePriceBins.get(taz.tazId.toString)
        .map(i => i(timeBinIndex).currentIterationSurgePriceLevel)
        .getOrElse(throw new Exception("no surge level found"))
    }
  }

  def addRideCost(time: Double, cost: Double, pickupLocation: Location): Unit = {
    if (tazTreeMap != null) {

      val taz = tazTreeMap.getTAZ(pickupLocation.getX, pickupLocation.getY)
      val timeBinIndex = getTimeBinIndex(time)

      surgePriceBins.get(taz.tazId.toString).foreach { i =>
        val surgePriceBin = i.apply(timeBinIndex)
        val updatedCurrentIterRevenue = surgePriceBin.currentIterationRevenue + cost
        val updatedBin = surgePriceBin.copy(currentIterationRevenue = updatedCurrentIterRevenue)
        i.update(timeBinIndex, updatedBin)
      }
    }
  }

  // TODO: print revenue each iteration out


  def updateRevenueStats() = {
    // TODO: is not functioning properly yet
    rideHailingRevenue.append(getCurrentIterationRevenueSum)
    rideHailingRevenue.foreach(println)
  }

  private def getCurrentIterationRevenueSum(): Double = {
    var sum: Double = 0
    surgePriceBins.values.foreach { i =>
      for (j <- 0 until i.size - 1) {
        val surgePriceBin = i.apply(j)
        sum += surgePriceBin.currentIterationRevenue
      }
    }
    sum
  }

  private def getTimeBinIndex(time: Double): Int = Math.round(time / timeBinSize).toInt - 1

}


// TO
case class SurgePriceBin(previousIterationRevenue: Double, currentIterationRevenue: Double, previousIterationSurgePriceLevel: Double, currentIterationSurgePriceLevel: Double)
