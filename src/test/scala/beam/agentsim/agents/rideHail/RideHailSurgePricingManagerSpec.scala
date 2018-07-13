package beam.agentsim.agents.rideHail

import beam.agentsim.infrastructure.TAZTreeMap
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.{Config, ConfigValueFactory}
import org.matsim.core.utils.misc.Time
import org.mockito.Mockito._
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{Matchers, WordSpecLike}

import scala.collection.JavaConverters._
import scala.util.{Random, Try}

class RideHailSurgePricingManagerSpec extends WordSpecLike with Matchers with MockitoSugar {

  val testConfigFileName = "test/input/beamville/beam.conf"
  val config: Config = testConfig(testConfigFileName)

  val beamConfig: BeamConfig = BeamConfig(config)
  val treeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)

  def getTazTreeMap(file: String): TAZTreeMap = {
    Try(TAZTreeMap.fromCsv(file)).getOrElse {
      RideHailSurgePricingManager.defaultTazTreeMap
    }
  }

  "RideHailSurgePricingManager" must {
    "be correctly initialized" in {
      val config = testConfig(testConfigFileName)
        .withValue(
          "beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy",
          ConfigValueFactory.fromAnyRef("CONTINUES_DEMAND_SUPPLY_MATCHING")
        )
      val beamConfig: BeamConfig = BeamConfig(config)
      val treeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)

      val rhspm = new RideHailSurgePricingManager(beamConfig, Some(treeMap))
      rhspm.surgePriceBins should have size treeMap.tazQuadTree.size()
      val expectedResult = SurgePriceBin(0.0, 0.0, 1.0, 1.0)
      rhspm.surgePriceBins.values.map(f => f.map(_ shouldBe expectedResult))
    }

    "correctly update SurgePriceLevels" in {
      val config = testConfig(testConfigFileName)
        .withValue(
          "beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy",
          ConfigValueFactory.fromAnyRef("CONTINUES_DEMAND_SUPPLY_MATCHING")
        )
      val beamConfig: BeamConfig = BeamConfig(config)
      val treeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)

      //First iteration random returns true
      val mockRandom = mock[Random]
      when(mockRandom.nextBoolean) thenReturn true

//      var random = new Random(){
//        override def nextBoolean(): Boolean = true
//      }

      var rhspm = new RideHailSurgePricingManager(beamConfig, Some(treeMap)) {
        override val rand: Random = mockRandom
      }

      var expectedValue = rhspm.surgePriceBins.map({ f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel + rhspm.surgeLevelAdaptionStep))
      })

      rhspm.updateSurgePriceLevels()
      var finalValue = rhspm.surgePriceBins.map({ f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel))
      })

      expectedValue shouldBe finalValue

      //Next iteration when true
      var expectedValue2 = rhspm.surgePriceBins.map {
        case (tazId, binsArray) =>
          (tazId, binsArray.map { binElem =>
            val updatedPreviousSurgePriceLevel =
              binElem.currentIterationSurgePriceLevel
            val updatedSurgeLevel = binElem.currentIterationSurgePriceLevel //+ (binElem.currentIterationSurgePriceLevel - binElem.previousIterationSurgePriceLevel)
            val updatedCurrentSurgePriceLevel =
              Math.max(updatedSurgeLevel, rhspm.minimumSurgeLevel)
            val updatedPrevIterRevenue = binElem.currentIterationRevenue
            val currentIterationRevenue = 0
            SurgePriceBin(
              updatedPrevIterRevenue,
              currentIterationRevenue,
              updatedPreviousSurgePriceLevel,
              updatedCurrentSurgePriceLevel
            )
          })
      }
      rhspm.updateSurgePriceLevels()
      expectedValue2 shouldBe rhspm.surgePriceBins

      //First iteration random returns false
      when(mockRandom.nextBoolean) thenReturn false
//      random = new Random(){
//        override def nextBoolean(): Boolean = false
//      }
      rhspm = new RideHailSurgePricingManager(beamConfig, Some(treeMap)) {
        override val rand: Random = mockRandom
      }

      expectedValue = rhspm.surgePriceBins.map({ f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel - rhspm.surgeLevelAdaptionStep))
      })

      rhspm.updateSurgePriceLevels()
      finalValue = rhspm.surgePriceBins.map({ f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel))
      })

      expectedValue shouldBe finalValue

      //Next iteration when false
      expectedValue2 = rhspm.surgePriceBins.map {
        case (tazId, binsArray) =>
          (tazId, binsArray.map { binElem =>
            val updatedPreviousSurgePriceLevel =
              binElem.currentIterationSurgePriceLevel
            val updatedSurgeLevel = binElem.currentIterationSurgePriceLevel //- (binElem.currentIterationSurgePriceLevel - binElem.previousIterationSurgePriceLevel)
            val updatedCurrentSurgePriceLevel =
              Math.max(updatedSurgeLevel, rhspm.minimumSurgeLevel)
            val updatedPrevIterRevenue = binElem.currentIterationRevenue
            val currentIterationRevenue = 0
            SurgePriceBin(
              updatedPrevIterRevenue,
              currentIterationRevenue,
              updatedPreviousSurgePriceLevel,
              updatedCurrentSurgePriceLevel
            )
          })
      }
      rhspm.updateSurgePriceLevels()
      expectedValue2 shouldBe rhspm.surgePriceBins
    }

    "correctly update previous iteration revenues and resetting current" in {
      val config = testConfig(testConfigFileName)
        .withValue(
          "beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy",
          ConfigValueFactory.fromAnyRef("CONTINUES_DEMAND_SUPPLY_MATCHING")
        )
      val beamConfig: BeamConfig = BeamConfig(config)
      val treeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)
      val rhspm = new RideHailSurgePricingManager(beamConfig, Some(treeMap))
      val expectedResultCurrentIterationRevenue = 0
      val initialValueCurrent =
        rhspm.surgePriceBins.map(f => (f._1, f._2.map(s => s.currentIterationRevenue)))

      rhspm.updatePreviousIterationRevenuesAndResetCurrent

      val finalValueRevenue =
        rhspm.surgePriceBins.map(f => (f._1, f._2.map(s => s.previousIterationRevenue)))

      initialValueCurrent shouldBe finalValueRevenue
      rhspm.surgePriceBins.values
        .map(f => f.map(_.currentIterationRevenue shouldBe expectedResultCurrentIterationRevenue))
    }

    "return fixed value of 1.0 when KEEP_PRICE_LEVEL_FIXED_AT_ONE used" in {
      val config = testConfig(testConfigFileName)
        .withValue(
          "beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy",
          ConfigValueFactory.fromAnyRef("KEEP_PRICE_LEVEL_FIXED_AT_ONE")
        )
      val beamConfig: BeamConfig = BeamConfig(config)
      val treeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)

      val rhspm = new RideHailSurgePricingManager(beamConfig, Some(treeMap))

      val tazArray = treeMap.tazQuadTree.values.asScala.toSeq
      val randomTaz = tazArray(Random.nextInt(tazArray.size))

      rhspm.getSurgeLevel(randomTaz.coord, 0) shouldEqual 1.0
    }

    "return correct surge level" in {
      val config = testConfig(testConfigFileName)
        .withValue(
          "beam.agentsim.agents.rideHail.surgePricing.priceAdjustmentStrategy",
          ConfigValueFactory.fromAnyRef("CONTINUES_DEMAND_SUPPLY_MATCHING")
        )
      val beamConfig: BeamConfig = BeamConfig(config)
      val treeMap: TAZTreeMap = getTazTreeMap(beamConfig.beam.agentsim.taz.file)

      val rhspm = new RideHailSurgePricingManager(beamConfig, Some(treeMap))

      val tazArray = treeMap.tazQuadTree.values.asScala.toSeq

      val randomTaz = tazArray(2)
      val timeBinSize = beamConfig.beam.agentsim.agents.rideHail.surgePricing.timeBinSize
      val hourRandom = 1
      val hourInSeconds = hourRandom * timeBinSize

      val expectedValue = rhspm.surgePriceBins(randomTaz.tazId.toString).apply(hourRandom)
      val surgeLevel = rhspm.getSurgeLevel(randomTaz.coord, hourInSeconds)

      surgeLevel shouldEqual expectedValue.currentIterationSurgePriceLevel
    }

    "correctly add ride cost" in {
      val rhspm = new RideHailSurgePricingManager(beamConfig, Some(treeMap))
      val tazArray = treeMap.tazQuadTree.values.asScala.toList

      val randomTaz = tazArray(2)
      val timeBinSize = beamConfig.beam.agentsim.agents.rideHail.surgePricing.timeBinSize
      val endTime =
        Math.ceil(Time.parseTime(beamConfig.matsim.modules.qsim.endTime) / timeBinSize).toInt
      val hourRandom = Random.nextInt(endTime)
      val hourInSeconds = hourRandom * timeBinSize
      val cost = 0.5
      val expectedValueCurrentIterationRevenue = 0.5

      rhspm.addRideCost(hourInSeconds, cost, randomTaz.coord)

      val arrayForTaz = rhspm.surgePriceBins(randomTaz.tazId.toString)
      val surgePriceBin = arrayForTaz(hourRandom)
      surgePriceBin.currentIterationRevenue should equal(expectedValueCurrentIterationRevenue)

    }
  }

}
