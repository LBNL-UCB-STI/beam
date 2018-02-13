package beam.agentsim.agents

import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig

import scala.collection.mutable

class RideHailSurgePricingManager(beamConfig: BeamConfig, val tazTreeMap: TAZTreeMap) {

  // TODO: load following parameters directly from config (add them there)
  val timeBinSize = 900;
  val numberOfTimeBins = 900*4*24;
  val surgeLevelAdaptionStep = 0.1;

  // TODO: initialize all bins (price levels and iteration revenues)!

  var revenueCurrentIteration: mutable.HashMap[String, Array[Double]] = new mutable.HashMap()
  var revenuePreviousIteration: mutable.HashMap[String, Array[Double]] = new mutable.HashMap()

  val surgePriceLevel: mutable.HashMap[String, Array[Double]] = new mutable.HashMap()

  def getEmptyDoubleArray(value:Double): Array[Double] ={
    val arr=Array[Double](numberOfTimeBins)
  //   arr.fill[Double](value)
    // TODO correct above line
    arr
  }

  // this should be invoked after each iteration

  // TODO: initialize in BEAMSim and also reset there after each iteration?
  def updateSurgePriceLevels(): Unit = {

    if (isFirstIteration){


      // TODO: randomly change surge price levels
    } else {
      // TODO: move surge price by step in direction of positive movement

    }

    updatePreviousIterationRevenuesAndResetCurrent
  }

  private def isFirstIteration = {
    revenuePreviousIteration.size == 0
  }

  private def updatePreviousIterationRevenuesAndResetCurrent = {
    for (key <- revenueCurrentIteration.keySet) {
      revenuePreviousIteration.put(key, revenueCurrentIteration.getOrElseUpdate(key, Array[Double]()))
    }
    revenueCurrentIteration.clear()
  }

  def getCostSurgeLevel(location: Location, time: Double): Double = {
  //  val taz = tazTreeMap.getId(location.getX, location.getY)
  //  val timeBinIndex = Math.round(time / timeBinSize);
  //  surgePriceLevel.get(taz.tazId.toString)(timeBinIndex)
    50.0
  }

  def addRideCost(time: Double, cost: Double, pickupLocation: Location): Unit = {
   // val taz = tazTreeMap.getId(pickupLocation.getX, pickupLocation.getY)
   // val timeBinIndex = Math.round(time / timeBinSize);
    //  revenueCurrentIteration.get(taz.tazId.toString)(timeBinIndex)=revenueCurrentIteration.get(taz.tazId.toString)(timeBinIndex)+cost;
    // TODO: repair/refactor line above
  }


  // TODO: print revenue each iteration out


}
