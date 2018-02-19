package beam.agents

import beam.agentsim.agents.{RideHailSurgePricingManager, SurgePriceBin}
import beam.agentsim.infrastructure.{TAZ, TAZTreeMap}
import beam.router.BeamRouter.Location
import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import org.matsim.core.utils.misc.Time
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class RideHailSurgePricingManagerSpec extends WordSpecLike with Matchers{

  val testConfigFileName = "test/input/beamville/beam.conf"
  val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(testConfigFileName)

  val beamConfig: BeamConfig = BeamConfig(config)
  val treeMap: TAZTreeMap = TAZTreeMap.fromCsv(beamConfig.beam.agentsim.taz.file)

  "RideHailSurgePricingManager" must {
    "be correctly initialized" in {
      val rhspm = new RideHailSurgePricingManager(beamConfig, treeMap)
      rhspm.surgePriceBins should have size treeMap.tazQuadTree.size()
      val expectedResult = SurgePriceBin(0.0, 0.0, 1.0, 1.0)
      rhspm.surgePriceBins.values.map( f => f.map(_ shouldBe(expectedResult)))
    }

    "be correctly updated SurgePriceLevels" in {
      val rhspm = new RideHailSurgePricingManager(beamConfig, treeMap)
      val initialValue = rhspm.surgePriceBins.map({
        f => (f._1 ,f._2.map( s => s.currentIterationSurgePriceLevel) )
      })
      rhspm.updateSurgePriceLevels()
      val finalValue = rhspm.surgePriceBins.map({
        f => (f._1 ,f._2.map( s => s.currentIterationSurgePriceLevel) )
      })

      initialValue should not be finalValue
    }

    "be updated Previous Iteration Revenues And Resetting Current" in {
      val rhspm = new RideHailSurgePricingManager(beamConfig, treeMap)
      val expectedResultcurrentIterationRevenue = 0
      val initialValueCurrent = rhspm.surgePriceBins.map(f => (f._1, f._2.map(s => s.currentIterationRevenue)))
      rhspm.updatePreviousIterationRevenuesAndResetCurrent

      val finalValueRevenue = rhspm.surgePriceBins.map(f => (f._1, f._2.map(s => s.previousIterationRevenue)))


      initialValueCurrent shouldBe( finalValueRevenue)
      rhspm.surgePriceBins.values.map( f => f.map(_.currentIterationRevenue shouldBe(expectedResultcurrentIterationRevenue)))

    }

    "be returned correct surge level" in {
      val rhspm = new RideHailSurgePricingManager(beamConfig, treeMap)
      val temp = rhspm.surgePriceBins
    }

    "be added ride cost is correct" in {
      val rhspm = new RideHailSurgePricingManager(beamConfig, treeMap)
      val tazArray = treeMap.tazQuadTree.values.asScala.toSeq

      println("")

      val randomTaz = tazArray(Random.nextInt( tazArray.size))
      val timeBinSize = beamConfig.beam.agentsim.agents.rideHailing.surgePricing.timeBinSize
      val endTime = Math.ceil(Time.parseTime(beamConfig.matsim.modules.qsim.endTime)  / timeBinSize).toInt
      val hourRandom = Random.nextInt(endTime)
      val hourInSeconds = hourRandom / timeBinSize
      val cost = 0.5
      val expectedValueCurrentIterationRevenue = 0.5

      rhspm.addRideCost(hourInSeconds, cost, randomTaz.coord)

      println("")

      val arrayForTaz = rhspm.surgePriceBins.get(randomTaz.tazId.toString).get
      println("")
      val surgePriceBin = arrayForTaz(hourRandom )

      surgePriceBin.currentIterationRevenue should equal(expectedValueCurrentIterationRevenue)


    }
  }

}
