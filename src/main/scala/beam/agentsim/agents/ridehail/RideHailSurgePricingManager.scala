package beam.agentsim.agents.ridehail

import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents
import com.google.inject.Inject
import org.matsim.core.utils.misc.Time

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class RideHailSurgePricingManager @Inject() (val beamServices: BeamServices) {
  import RideHailSurgePricingManager._

  val rideHailConfig: Agents.RideHail = beamServices.beamConfig.beam.agentsim.agents.rideHail

  // TODO:

  // when ever turning around direction, make the step half as large after a certain iteration number, which is specified

  // max price define
  // slowing down price change according to revenue change?
  // fix the KEEP_PRICE_LEVEL_FIXED_AT_ONE price levels below
  // define other strategies for this?

  // TODO: can we allow any other class to inject taz as well, without loading multiple times? (Done)
  val timeBinSize: Int =
    beamServices.beamConfig.beam.agentsim.timeBinSize // TODO: does throw exception for 60min, if +1 missing below
  val numberOfCategories: Int =
    rideHailConfig.surgePricing.numberOfCategories // TODO: does throw exception for 0 and negative values
  val numberOfTimeBins: Int = Math
    .floor(Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime) / timeBinSize)
    .toInt + 1
  val surgeLevelAdaptionStep: Double = rideHailConfig.surgePricing.surgeLevelAdaptionStep
  val minimumSurgeLevel: Double = rideHailConfig.surgePricing.minimumSurgeLevel
  // TODO: implement all cases for these surge prices properly
  val CONTINUES_DEMAND_SUPPLY_MATCHING = "CONTINUES_DEMAND_SUPPLY_MATCHING"
  val KEEP_PRICE_LEVEL_FIXED_AT_ONE = "KEEP_PRICE_LEVEL_FIXED_AT_ONE"
  val rideHailRevenue: ArrayBuffer[Double] = ArrayBuffer[Double]()
  val defaultBinContent: SurgePriceBin = SurgePriceBin(0.0, 0.0, 1.0, 1.0)

  //Scala like code
  val surgePriceBins: Map[String, ArrayBuffer[SurgePriceBin]] =
    beamServices.beamScenario.tazTreeMap.tazQuadTree.values.asScala.map { v =>
      val array = (0 until numberOfTimeBins).foldLeft(new ArrayBuffer[SurgePriceBin]) { (arrayBuffer, _) =>
        arrayBuffer.append(defaultBinContent)
        arrayBuffer
      }
      (v.tazId.toString, array)
    }.toMap
  val rand = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
  var iteration = 0
  var isFirstIteration = true

  //  var surgePriceBins: HashMap[String, ArraySeq[SurgePriceBin]] = new HashMap()
  var maxSurgePricingLevel: Double = 0
  var surgePricingLevelCount: Int = 0

  // TODO: add system iteration revenue in class (add after each iteration), so that it can be accessed during graph generation!

  // TODO: initialize all bins (price levels and iteration revenues)!
  var totalSurgePricingLevel: Double = 0
  var priceAdjustmentStrategy: String = rideHailConfig.surgePricing.priceAdjustmentStrategy

  // this should be invoked after each iteration
  // TODO: initialize in BEAMSim and also reset there after each iteration?
  def updateSurgePriceLevels(): Unit = {

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
            if (surgePriceBin.currentIterationRevenue.equals(surgePriceBin.previousIterationRevenue)) {
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

  def updatePreviousIterationRevenuesAndResetCurrent(): Unit = {
    updateForAllElements(surgePriceBins) { surgePriceBin =>
      val updatedPrevIterRevenue = surgePriceBin.currentIterationRevenue
      surgePriceBin.copy(
        previousIterationRevenue = updatedPrevIterRevenue,
        currentIterationRevenue = 0
      )
    }
  }

  def getSurgeLevel(location: Location, time: Double): Double = {
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

  private def getTimeBinIndex(time: Double): Int = Math.floor(time / timeBinSize).toInt // - 1

  // TODO: print revenue each iteration out

  def addRideCost(time: Double, cost: Double, pickupLocation: Location): Unit = {

    val taz = beamServices.beamScenario.tazTreeMap.getTAZ(pickupLocation.getX, pickupLocation.getY)
    val timeBinIndex = getTimeBinIndex(time)

    surgePriceBins.get(taz.tazId.toString).foreach { i =>
      if (timeBinIndex < i.size) {
        val surgePriceBin = i.apply(timeBinIndex)
        val updatedCurrentIterRevenue = surgePriceBin.currentIterationRevenue + cost
        val updatedBin = surgePriceBin.copy(currentIterationRevenue = updatedCurrentIterRevenue)
        i.update(timeBinIndex, updatedBin)
      }
    }
  }

  def updateRevenueStats(): Unit = {
    // TODO: is not functioning properly yet
    rideHailRevenue.append(getCurrentIterationRevenueSum)
    //rideHailRevenue.foreach(println)
  }

  private def getCurrentIterationRevenueSum: Double = {
    var sum: Double = 0
    surgePriceBins.values.foreach { i =>
      for (j <- 0 until i.size - 1) {
        val surgePriceBin = i.apply(j)
        sum += surgePriceBin.currentIterationRevenue
        surgePricingLevelCount += 1
        totalSurgePricingLevel += surgePriceBin.currentIterationSurgePriceLevel
        if (maxSurgePricingLevel < surgePriceBin.currentIterationSurgePriceLevel) {
          maxSurgePricingLevel = surgePriceBin.currentIterationSurgePriceLevel
        }
      }
    }
    sum
  }

  def incrementIteration(): Unit = {
    iteration += 1
    surgePricingLevelCount = 0
    totalSurgePricingLevel = 0
    maxSurgePricingLevel = 0
  }

  def getIterationNumber: Int = {
    iteration
  }

}

object RideHailSurgePricingManager {

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

}

// TODO put in companion object
case class SurgePriceBin(
  previousIterationRevenue: Double,
  currentIterationRevenue: Double,
  previousIterationSurgePriceLevel: Double,
  currentIterationSurgePriceLevel: Double
)
