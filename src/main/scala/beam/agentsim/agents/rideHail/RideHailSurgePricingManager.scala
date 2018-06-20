package beam.agentsim.agents.rideHail

import beam.agentsim.infrastructure.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.misc.Time

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

object RideHailSurgePricingManager {
  val defaultTazTreeMap: TAZTreeMap = {
    val tazQuadTree: QuadTree[TAZ] = new QuadTree[TAZ](-1, -1, 1, 1)
    val taz = new TAZ("0", new Coord(0.0, 0.0))
    tazQuadTree.put(taz.coord.getX, taz.coord.getY, taz)
    new TAZTreeMap(tazQuadTree)
  }
}

class RideHailSurgePricingManager(beamConfig: BeamConfig, mTazTreeMap: Option[TAZTreeMap]) {

  var iteration = 0


  // TODO:

  // when ever turning around direction, make the step half as large after a certain iteration number, which is specified


  // max price define
  // slowing down price change according to revenue change?
  // fix the KEEP_PRICE_LEVEL_FIXED_AT_ONE price levels below
  // define other strategies for this?


  // TODO: load following parameters directly from config (add them there)zz

  // TODO: can we allow any other class to inject taz as well, without loading multiple times? (Done)

  val rideHaillingConfig = beamConfig.beam.agentsim.agents.rideHailing
  val timeBinSize = rideHaillingConfig.surgePricing.timeBinSize // TODO: does throw exception for 60min, if +1 missing below
  val numberOfCategories = rideHaillingConfig.surgePricing.numberOfCategories // TODO: does throw exception for 0 and negative values
  val numberOfTimeBins = Math.floor(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSize).toInt+1
  val surgeLevelAdaptionStep = rideHaillingConfig.surgePricing.surgeLevelAdaptionStep
  val minimumSurgeLevel = rideHaillingConfig.surgePricing.minimumSurgeLevel
  var isFirstIteration = true

  // TODO: implement all cases for these surge prices properly
  val CONTINUES_DEMAND_SUPPLY_MATCHING = "CONTINUES_DEMAND_SUPPLY_MATCHING"
  val KEEP_PRICE_LEVEL_FIXED_AT_ONE = "KEEP_PRICE_LEVEL_FIXED_AT_ONE"

  var priceAdjustmentStrategy = rideHaillingConfig.surgePricing.priceAdjustmentStrategy

  //  var surgePriceBins: HashMap[String, ArraySeq[SurgePriceBin]] = new HashMap()

  val rideHailingRevenue = ArrayBuffer[Double]()

  val defaultBinContent = SurgePriceBin(0.0, 0.0, 1.0, 1.0)

  // TODO: add system iteration revenue in class (add after each iteration), so that it can be accessed during graph generation!

  // TODO: initialize all bins (price levels and iteration revenues)!

  private val tazTreeMap = mTazTreeMap.getOrElse(RideHailSurgePricingManager.defaultTazTreeMap)

  //Scala like code
  val surgePriceBins: Map[String, ArrayBuffer[SurgePriceBin]] = tazTreeMap.tazQuadTree
    .values
    .asScala
    .map { v =>
      val array = (0 until numberOfTimeBins).foldLeft(new ArrayBuffer[SurgePriceBin]) { (arrayBuffer, _) =>
        arrayBuffer.append(defaultBinContent)
        arrayBuffer
      }
      (v.tazId.toString, array)
    }.toMap

  val rand = new Random

  // this should be invoked after each iteration
  // TODO: initialize in BEAMSim and also reset there after each iteration?
  def updateSurgePriceLevels(): Unit = {

    if (!priceAdjustmentStrategy.equalsIgnoreCase(KEEP_PRICE_LEVEL_FIXED_AT_ONE)) {
      if (isFirstIteration) {
        // TODO: can we refactor the following two blocks of code to reduce duplication?

        // TODO: seed following random to some config seed?

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
          val updatedSurgeLevel = if (surgePriceBin.currentIterationRevenue == surgePriceBin.previousIterationRevenue) {
            surgePriceBin.currentIterationSurgePriceLevel
          } else {
            if (surgePriceBin.currentIterationRevenue > surgePriceBin.previousIterationRevenue) {
              surgePriceBin.currentIterationSurgePriceLevel + (surgePriceBin.currentIterationSurgePriceLevel - surgePriceBin.previousIterationSurgePriceLevel)
            } else {
              surgePriceBin.currentIterationSurgePriceLevel - (surgePriceBin.currentIterationSurgePriceLevel - surgePriceBin.previousIterationSurgePriceLevel)
            }
          }
          surgePriceBin.copy(previousIterationSurgePriceLevel = updatedPreviousSurgePriceLevel, currentIterationSurgePriceLevel = Math.max(updatedSurgeLevel, minimumSurgeLevel))
        }
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

  def updatePreviousIterationRevenuesAndResetCurrent = {
    updateForAllElements(surgePriceBins) { surgePriceBin =>
      val updatedPrevIterRevenue = surgePriceBin.currentIterationRevenue
      surgePriceBin.copy(previousIterationRevenue = updatedPrevIterRevenue, currentIterationRevenue = 0)
    }
  }


  def getSurgeLevel(location: Location, time: Double): Double = {
    val taz = tazTreeMap.getTAZ(location.getX, location.getY)
    val timeBinIndex = getTimeBinIndex(time)
    surgePriceBins.get(taz.tazId.toString)
      .map{i =>
        if(timeBinIndex < i.size){
          i(timeBinIndex).currentIterationSurgePriceLevel
        }else{
          1.0
        }
      }.getOrElse(throw new Exception("no surge level found"))
  }

  def addRideCost(time: Double, cost: Double, pickupLocation: Location): Unit = {

    val taz = tazTreeMap.getTAZ(pickupLocation.getX, pickupLocation.getY)
    val timeBinIndex = getTimeBinIndex(time)

    surgePriceBins.get(taz.tazId.toString).foreach { i =>
      if(timeBinIndex < i.size) {
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
    //rideHailingRevenue.foreach(println)
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

  private def getTimeBinIndex(time: Double): Int = Math.floor(time / timeBinSize).toInt // - 1

  def incrementIteration() = {
    iteration += 1
  }

  def getIterationNumber() = {
    iteration
  }
}


// TODO put in companion object
case class SurgePriceBin(previousIterationRevenue: Double, currentIterationRevenue: Double, previousIterationSurgePriceLevel: Double, currentIterationSurgePriceLevel: Double)
