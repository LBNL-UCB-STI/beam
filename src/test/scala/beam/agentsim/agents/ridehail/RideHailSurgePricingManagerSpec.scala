package beam.agentsim.agents.ridehail

import beam.sim.{BeamHelper, BeamScenario, BeamServices}
import beam.sim.config.{BeamConfig, BeamExecutionConfig}
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.Config
import org.matsim.core.utils.misc.Time
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._
import scala.util.Random
import com.google.inject.Injector
import org.matsim.core.scenario.MutableScenario
import org.mockito.Mockito

class RideHailSurgePricingManagerSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAll {

  val testConfigFileName = "test/input/beamville/beam.conf"
  val config: Config = testConfig(testConfigFileName).resolve()
  lazy val beamConfig: BeamConfig = BeamConfig(config)
  val beamExecConfig: BeamExecutionConfig = setupBeamWithConfig(config)
  lazy val beamScenario: BeamScenario = loadScenario(beamExecConfig.beamConfig)
  lazy val scenario: MutableScenario = buildScenarioFromMatsimConfig(beamExecConfig.matsimConfig, beamScenario)
  lazy val injector: Injector = buildInjector(config, beamExecConfig.beamConfig, scenario, beamScenario)
  lazy val beamServices: BeamServices = buildBeamServices(injector)

  override def afterAll(): Unit = {
    injector.getInstance(classOf[org.matsim.analysis.TravelDistanceStats]).close()
    super.afterAll()
  }

  "RideHailSurgePricingManager" must {
    "be correctly initialized" in {
      val surgePricingManager = new RideHailSurgePricingManager(beamServices)
      surgePricingManager.priceAdjustmentStrategy = "CONTINUES_DEMAND_SUPPLY_MATCHING"
      surgePricingManager.surgePriceBins should have size beamScenario.tazTreeMap.tazQuadTree.size()
      val expectedResult = SurgePriceBin(0.0, 0.0, 1.0, 1.0)
      surgePricingManager.surgePriceBins.values.map(f => f.map(_ shouldBe expectedResult))
    }

    "correctly update SurgePriceLevels" in {
      //First iteration random returns true
      val mockRandom = Mockito.mock(classOf[Random])
      when(mockRandom.nextBoolean) thenReturn true

      var rhspm = new RideHailSurgePricingManager(beamServices) {
        override val rand: Random = mockRandom
      }
      rhspm.priceAdjustmentStrategy = "CONTINUES_DEMAND_SUPPLY_MATCHING"

      var expectedValue = rhspm.surgePriceBins.map { f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel + rhspm.surgeLevelAdaptionStep))
      }

      rhspm.updateSurgePriceLevels()
      var finalValue = rhspm.surgePriceBins.map { f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel))
      }

      expectedValue shouldBe finalValue

      //Next iteration when true
      var expectedValue2 = rhspm.surgePriceBins.map { case (tazId, binsArray) =>
        (
          tazId,
          binsArray.map { binElem =>
            val updatedPreviousSurgePriceLevel =
              binElem.currentIterationSurgePriceLevel
            val updatedSurgeLevel =
              binElem.currentIterationSurgePriceLevel //+ (binElem.currentIterationSurgePriceLevel - binElem.previousIterationSurgePriceLevel)
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
          }
        )
      }
      rhspm.updateSurgePriceLevels()
      expectedValue2 shouldBe rhspm.surgePriceBins

      //First iteration random returns false
      when(mockRandom.nextBoolean) thenReturn false
//      random = new Random(){
//        override def nextBoolean(): Boolean = false
//      }
      rhspm = new RideHailSurgePricingManager(beamServices) {
        override val rand: Random = mockRandom
      }
      rhspm.priceAdjustmentStrategy = "CONTINUES_DEMAND_SUPPLY_MATCHING"

      expectedValue = rhspm.surgePriceBins.map { f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel - rhspm.surgeLevelAdaptionStep))
      }

      rhspm.updateSurgePriceLevels()
      finalValue = rhspm.surgePriceBins.map { f =>
        (f._1, f._2.map(s => s.currentIterationSurgePriceLevel))
      }

      expectedValue shouldBe finalValue

      //Next iteration when false
      expectedValue2 = rhspm.surgePriceBins.map { case (tazId, binsArray) =>
        (
          tazId,
          binsArray.map { binElem =>
            val updatedPreviousSurgePriceLevel =
              binElem.currentIterationSurgePriceLevel
            val updatedSurgeLevel =
              binElem.currentIterationSurgePriceLevel //- (binElem.currentIterationSurgePriceLevel - binElem.previousIterationSurgePriceLevel)
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
          }
        )
      }
      rhspm.updateSurgePriceLevels()
      expectedValue2 shouldBe rhspm.surgePriceBins
    }

    "correctly update previous iteration revenues and resetting current" in {
      val rhspm = new RideHailSurgePricingManager(beamServices)
      rhspm.priceAdjustmentStrategy = "CONTINUES_DEMAND_SUPPLY_MATCHING"
      val expectedResultCurrentIterationRevenue = 0
      val initialValueCurrent =
        rhspm.surgePriceBins.map(f => (f._1, f._2.map(s => s.currentIterationRevenue)))

      rhspm.updatePreviousIterationRevenuesAndResetCurrent()

      val finalValueRevenue =
        rhspm.surgePriceBins.map(f => (f._1, f._2.map(s => s.previousIterationRevenue)))

      initialValueCurrent shouldBe finalValueRevenue
      rhspm.surgePriceBins.values
        .map(f => f.map(_.currentIterationRevenue shouldBe expectedResultCurrentIterationRevenue))
    }

    "return fixed value of 1.0 when KEEP_PRICE_LEVEL_FIXED_AT_ONE used" in {
      val rhspm = new RideHailSurgePricingManager(beamServices)
      rhspm.priceAdjustmentStrategy = "KEEP_PRICE_LEVEL_FIXED_AT_ONE"

      val tazArray = beamScenario.tazTreeMap.tazQuadTree.values.asScala.toSeq
      val randomTaz = tazArray(Random.nextInt(tazArray.size))

      rhspm.getSurgeLevel(randomTaz.coord, 0) shouldEqual 1.0
    }

    "return correct surge level" in {
      val rhspm = new RideHailSurgePricingManager(beamServices)
      rhspm.priceAdjustmentStrategy = "CONTINUES_DEMAND_SUPPLY_MATCHING"

      val tazArray = beamScenario.tazTreeMap.tazQuadTree.values.asScala.toSeq

      val randomTaz = tazArray(2)
      val timeBinSize = beamConfig.beam.agentsim.timeBinSize
      val hourRandom = 1
      val hourInSeconds = hourRandom * timeBinSize

      val expectedValue = rhspm.surgePriceBins(randomTaz.tazId.toString).apply(hourRandom)
      val surgeLevel = rhspm.getSurgeLevel(randomTaz.coord, hourInSeconds)

      surgeLevel shouldEqual expectedValue.currentIterationSurgePriceLevel
    }

    "correctly add ride cost" in {
      val rhspm = new RideHailSurgePricingManager(beamServices)
      val tazArray = beamScenario.tazTreeMap.tazQuadTree.values.asScala.toList

      val randomTaz = tazArray(2)
      val timeBinSize = beamConfig.beam.agentsim.timeBinSize
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
