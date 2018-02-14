package beam.agentsim.agents

import beam.agentsim.infrastructure.TAZTreeMap
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig

import scala.collection.mutable
import scala.collection.mutable.{ArrayBuffer, ArraySeq, HashMap}
import scala.collection.JavaConverters._
import scala.util.Random

class RideHailSurgePricingManager(beamConfig: BeamConfig, val tazTreeMap: TAZTreeMap) {

  // TODO: load following parameters directly from config (add them there)
  val timeBinSize = 15*60;
  val numberOfTimeBins = 3600*24/timeBinSize;
  val surgeLevelAdaptionStep = 0.1;
  var isFirstIteration=true

  var surgePriceBins: HashMap[String, ArraySeq[SurgePriceBin]] = new HashMap()

  // TODO: initialize all bins (price levels and iteration revenues)!

  if (tazTreeMap!=null) {
    tazTreeMap.tazQuadTree.values().asScala.foreach { i =>
      val taz = i.tazId.toString
      val arr = ArrayBuffer[SurgePriceBin]()
      for (_ <- 0 to numberOfTimeBins - 1) {
        arr.append(SurgePriceBin(0.0, 0.0, 1.0, 1.0))
      }
      surgePriceBins.put(taz, ArraySeq[SurgePriceBin](arr.toArray: _*))
    }
  }


//println()


  // this should be invoked after each iteration

  // TODO: initialize in BEAMSim and also reset there after each iteration?
  def updateSurgePriceLevels(): Unit = {

    if (isFirstIteration){
      // TODO: can we refactor the following two blocks of code to reduce duplication?

      // TODO: seed following random to some config seed?
      val rand=Random
      surgePriceBins.values.foreach{ binArray =>
        for (j <- 0 to binArray.size-1){
         // print(j+ "-")
          val surgePriceBin = binArray.apply(j)
          val updatedSurgeLevel = if(rand.nextBoolean()){
            surgePriceBin.currentIterationSurgePriceLevel + surgeLevelAdaptionStep
          } else {
            surgePriceBin.currentIterationSurgePriceLevel - surgeLevelAdaptionStep
          }
          val updatedBin=surgePriceBin.copy(currentIterationSurgePriceLevel = updatedSurgeLevel)

          binArray.update(j,updatedBin)
        }
       // println()
      }

      isFirstIteration=false
    } else {
      // TODO: move surge price by step in direction of positive movement
   //   iterate over all items
      surgePriceBins.values.foreach{ binArray =>
        for (j <- 0 to binArray.size-1){
          val surgePriceBin = binArray.apply(j)
          val updatedPreviousSurgePriceLevel=surgePriceBin.currentIterationSurgePriceLevel;
          val updatedSurgeLevel = if(surgePriceBin.currentIterationRevenue > surgePriceBin.previousIterationRevenue){
            surgePriceBin.currentIterationSurgePriceLevel + surgeLevelAdaptionStep
          } else {
            surgePriceBin.currentIterationSurgePriceLevel - surgeLevelAdaptionStep
          }
          val updatedBin=surgePriceBin.copy(previousIterationSurgePriceLevel=updatedPreviousSurgePriceLevel, currentIterationSurgePriceLevel = updatedSurgeLevel)
          binArray.update(j,updatedBin)
        }
      }
    }

    updatePreviousIterationRevenuesAndResetCurrent
  }



  private def updatePreviousIterationRevenuesAndResetCurrent = {
    surgePriceBins.values.foreach{ i =>
      for (j <- 0 to i.size-1){

        val surgePriceBin=i.apply(j)
        val updatedPrevIterRevenue=surgePriceBin.currentIterationRevenue
        val updatedBin=surgePriceBin.copy(previousIterationRevenue=updatedPrevIterRevenue,currentIterationRevenue = -1)
        i.update(j,updatedBin)
      }
    }
  }

  def getSurgeLevel(location: Location, time: Double): Double = {
    if (tazTreeMap==null) return 1.0;


    val taz = tazTreeMap.getId(location.getX, location.getY)
    val timeBinIndex = Math.round(time / timeBinSize).toInt;
    surgePriceBins.get(taz.tazId.toString).map(i => i(timeBinIndex).previousIterationSurgePriceLevel).getOrElse(throw new Exception("no surge level found"))
  }

  def addRideCost(time: Double, cost: Double, pickupLocation: Location): Unit = {
    if (tazTreeMap==null) return;

    val taz = tazTreeMap.getId(pickupLocation.getX, pickupLocation.getY)
    val timeBinIndex = Math.round(time / timeBinSize).toInt;

    surgePriceBins.get(taz.tazId.toString).foreach{ i =>
      val surgePriceBin=i.apply(timeBinIndex)
      val updatedCurrentIterRevenue=surgePriceBin.currentIterationRevenue+ cost
      val updatedBin=surgePriceBin.copy(currentIterationRevenue=updatedCurrentIterRevenue)
      i.update(timeBinIndex,updatedBin)
    }
  }
  // TODO: print revenue each iteration out


}


// TO
case class SurgePriceBin(previousIterationRevenue: Double, currentIterationRevenue: Double, previousIterationSurgePriceLevel:Double, currentIterationSurgePriceLevel:Double)
