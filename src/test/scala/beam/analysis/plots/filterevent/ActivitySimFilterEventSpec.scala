package beam.analysis.plots.filterevent

import scala.util.Random

import beam.agentsim.events.ModeChoiceEvent
import beam.router.model.{EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.config.BeamConfig
import beam.utils.TestConfigUtils.testConfig
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, PlanElement}
import org.matsim.core.controler.MatsimServices
import org.mockito.Mockito
import org.mockito.Mockito.doAnswer
import org.scalatest.{Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar

class ActivitySimFilterEventSpec extends WordSpecLike with MockitoSugar with Matchers {

  private val beamConfigEnabled = BeamConfig(
    ConfigFactory
      .parseString(s"beam.exchange.scenario.urbansim.activitySimEnabled = true")
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
  )
  private val beamConfigDisabled = BeamConfig(
    ConfigFactory
      .parseString(s"beam.exchange.scenario.urbansim.activitySimEnabled = false")
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
  )
  private val matsimServices = mock[MatsimServices]

  private val activityTypeHome = "Home"
  private val activityHome = buildActivity(activityTypeHome)

  private val activityTypeWork = "Work"
  private val activityWork: Activity = buildActivity(activityTypeWork)

  private val activityTypeNotHomeNorWork = Random.nextString(10)
  private val activityNotHomeNorWork = buildActivity(activityTypeNotHomeNorWork)

  "ActivitySimFilterEvent graphNamePreSuffix" must {
    "return correct infix regardless of being enabled/disabled" in {
      val beamConfig = choose(beamConfigDisabled, beamConfigEnabled)
      val eventFilterEnabled = new ActivitySimFilterEvent(beamConfig, matsimServices)
      eventFilterEnabled.graphNamePreSuffix should be("_commute")
    }
  }

  "ActivitySimFilterEvent when processing ModeChoice event" must {
    "process event when is enabled and plans contains valid plans" in {
      val validPlans = Seq(IndexedSeq(activityWork, activityHome), IndexedSeq(activityHome, activityWork))
      val event = buildModeChoice(activityTypeHome)
      val eventFilter = buildActivitySimFilter(beamConfigEnabled, event, choose(validPlans: _*))
      val result = eventFilter.shouldProcessEvent(event)
      assert(result)
    }

    "NOT process event when is disabled and contains valid plans" in {
      val validPlans = Seq(IndexedSeq(activityWork, activityHome), IndexedSeq(activityHome, activityWork))
      val event = buildModeChoice(activityTypeHome)
      val eventFilter = buildActivitySimFilter(beamConfigDisabled, event, choose(validPlans: _*))
      val result = eventFilter.shouldProcessEvent(event)
      assert(!result)
    }

    "NOT process event when is enabled and contains invalid relations activity plans" in {
      val invalidPlans = Seq(
        IndexedSeq(activityHome, activityNotHomeNorWork),
        IndexedSeq(activityNotHomeNorWork, activityHome),
        IndexedSeq(activityNotHomeNorWork, activityWork),
        IndexedSeq(activityWork, activityNotHomeNorWork)
      )
      val event = buildModeChoice(activityTypeHome)
      val eventFilter =
        buildActivitySimFilter(beamConfigEnabled, event, choose(invalidPlans: _*))
      val result = eventFilter.shouldProcessEvent(event)
      assert(!result)
    }
  }

  private def buildActivitySimFilter(
    beamConfig: BeamConfig,
    event: ModeChoiceEvent,
    eventPlans: IndexedSeq[PlanElement]
  ): ActivitySimFilterEvent = {
    val result = Mockito.spy(new ActivitySimFilterEvent(beamConfig, matsimServices))
    doAnswer(_ => eventPlans).when(result).eventPlans(event)
    result
  }

  private def buildModeChoice(chosenMode: String): ModeChoiceEvent = {
    val tourIndex = 1
    new ModeChoiceEvent(
      Random.nextDouble(),
      Id.createPersonId(Random.nextInt()),
      chosenMode,
      Random.nextString(10),
      Random.nextDouble(),
      Random.nextString(10),
      Random.nextString(10),
      Random.nextBoolean(),
      Random.nextDouble(),
      tourIndex,
      EmbodiedBeamTrip(IndexedSeq.empty[EmbodiedBeamLeg])
    )
  }

  private def buildActivity(activityType: String): Activity = {
    val result = mock[Activity]
    Mockito.when(result.getType).thenReturn(activityType)
    result
  }

  private def choose[T](elements: T*): T = {
    val list = elements.toIndexedSeq
    list(Random.nextInt(list.length))
  }

}
