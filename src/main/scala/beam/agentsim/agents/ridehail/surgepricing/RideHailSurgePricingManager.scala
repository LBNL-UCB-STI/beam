package beam.agentsim.agents.ridehail.surgepricing

import beam.agentsim.agents.ridehail.surgepricing.RideHailSurgePricingManager.SurgePriceBin
import beam.router.BeamRouter.Location
import beam.sim.BeamServices
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents
import org.matsim.core.utils.misc.Time

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

trait RideHailSurgePricingManager {

  val beamServices: BeamServices
  val rideHailConfig: Agents.RideHail = beamServices.beamConfig.beam.agentsim.agents.rideHail

  val rand = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)

  //  var surgePriceBins: HashMap[String, ArraySeq[SurgePriceBin]] = new HashMap()
  var maxSurgePricingLevel: Double = 0
  var surgePricingLevelCount: Int = 0
  var iteration = 0
  var isFirstIteration = true


  // TODO: can we allow any other class to inject taz as well, without loading multiple times? (Done)
  val timeBinSize
  : Int = beamServices.beamConfig.beam.agentsim.timeBinSize // TODO: does throw exception for 60min, if +1 missing below
  val numberOfCategories
  : Int = rideHailConfig.surgePricing.numberOfCategories // TODO: does throw exception for 0 and negative values
  val numberOfTimeBins: Int = Math
    .floor(Time.parseTime(beamServices.beamConfig.matsim.modules.qsim.endTime) / timeBinSize)
    .toInt + 1
  val surgeLevelAdaptionStep: Double = rideHailConfig.surgePricing.surgeLevelAdaptionStep
  val minimumSurgeLevel: Double = rideHailConfig.surgePricing.minimumSurgeLevel
  // TODO: implement all cases for these surge prices properly
  val CONTINUES_DEMAND_SUPPLY_MATCHING = "CONTINUES_DEMAND_SUPPLY_MATCHING"
  val KEEP_PRICE_LEVEL_FIXED_AT_ONE = "KEEP_PRICE_LEVEL_FIXED_AT_ONE"
  val rideHailRevenue: ArrayBuffer[Double] = ArrayBuffer[Double]()
  val defaultBinContent = SurgePriceBin(0.0, 0.0, 1.0, 1.0)


  // TODO: add system iteration revenue in class (add after each iteration), so that it can be accessed during graph generation!

  // TODO: initialize all bins (price levels and iteration revenues)!
  var totalSurgePricingLevel: Double = 0

  //Scala like code
  val surgePriceBins: Map[String, ArrayBuffer[SurgePriceBin]] =
    beamServices.beamScenario.tazTreeMap.tazQuadTree.values.asScala.map { v =>
      val array = (0 until numberOfTimeBins).foldLeft(new ArrayBuffer[SurgePriceBin]) { (arrayBuffer, _) =>
        arrayBuffer.append(defaultBinContent)
        arrayBuffer
      }
      (v.tazId.toString, array)
    }.toMap

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


  def incrementIteration(): Unit = {
    iteration += 1
    surgePricingLevelCount = 0
    totalSurgePricingLevel = 0
    maxSurgePricingLevel = 0
  }

  def getIterationNumber: Int = {
    iteration
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

  def updateRevenueStats(): Unit = {
    // TODO: is not functioning properly yet
    rideHailRevenue.append(getCurrentIterationRevenueSum)
    //rideHailRevenue.foreach(println)
  }

  def getTimeBinIndex(time: Double): Int = Math.floor(time / timeBinSize).toInt // - 1

  // this should be invoked after each iteration
  // TODO: initialize in BEAMSim and also reset there after each iteration?
  // this should be invoked after each iteration
  def updateSurgePriceLevels(): Unit = {
      if (isFirstIteration) {
        updateForAllElements(surgePriceBins) {
          initializeSurgePricingLevel
        }
        isFirstIteration = false
      } else {
        updateForAllElements(surgePriceBins) {
          updateSurgePricingLevel
        }
      }
      updatePreviousIterationRevenuesAndResetCurrent()
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

  //Method to avoid code duplication
  def updateForAllElements(
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

  def initializeSurgePricingLevel(surgePriceBin: SurgePriceBin): SurgePriceBin

  def updateSurgePricingLevel(surgePriceBin: SurgePriceBin): SurgePriceBin

}

object RideHailSurgePricingManager {

  case class SurgePriceBin(
                            previousIterationRevenue: Double,
                            currentIterationRevenue: Double,
                            previousIterationSurgePriceLevel: Double,
                            currentIterationSurgePriceLevel: Double
                          )

  def getManagerClass(managerType:String):Class[_<:RideHailSurgePricingManager]={
    managerType match {
      case "FileBased"=>classOf[FileBasedRideHailSurgePricingManager]
      case "Adaptive"=>classOf[AdaptiveRideHailSurgePricingManager]
      case _ => classOf[DummyRideHailSurgePricingManager]
    }
  }
}
