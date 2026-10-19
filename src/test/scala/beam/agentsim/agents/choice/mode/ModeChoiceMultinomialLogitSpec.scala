package beam.agentsim.agents.choice.mode

import scala.util.Random

import beam.agentsim.agents.choice.mode.ModeChoiceMultinomialLogitSpec.{randomIntList, randomPath}
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.r5.{BikeLanesAdjustment, BikeLanesData}
import beam.router.skim.readonly.TransitCrowdingSkims
import beam.sim.config.{BeamConfig, BeamConfigHolder}
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import beam.sim.{BeamConfigChangesObservable, BeamScenario, BeamServices}
import beam.utils.TestConfigUtils.testConfig
import org.matsim.api.core.v01.Id
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.population.PopulationUtils
import org.matsim.vehicles.Vehicle
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.{mock, when}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class ModeChoiceMultinomialLogitSpec extends AnyWordSpec with Matchers {

  "ModeChoiceMultinomialLogit" when {
    "pathScaledForWaiting" must {
      "skip the first element of BeamPath and sum all travelTime when bikeLaneScaleFactor is 1" in {
        val value1 = 11.4d
        val value2 = 12.3d
        val path = randomPath(travelTimes = 10d, value1, value2)
        val bikeLaneLinkIds = randomIntList.toSet
        val data = BikeLanesData(scaleFactorFromConfig = 1d, bikeLanesLinkIds = bikeLaneLinkIds)
        val adjustment = new BikeLanesAdjustment(data)

        val result: Double = ModeChoiceMultinomialLogit.pathScaledForWaiting(path, adjustment)

        val clue = s"Input path: [$path]; bikeLaneLinkIds: [$bikeLaneLinkIds]"
        assert(result === value1 + value2, clue)
      }

      "be zero when path has zero or only one element" in {
        val path = if (Random.nextBoolean()) randomPath() else randomPath(travelTimes = 10d)
        val scaleFactor = Random.nextDouble()
        val bikeLaneLinkIds = randomIntList.toSet
        val data = BikeLanesData(scaleFactorFromConfig = scaleFactor, bikeLanesLinkIds = bikeLaneLinkIds)
        val adjustment = new BikeLanesAdjustment(data)

        val result: Double = ModeChoiceMultinomialLogit.pathScaledForWaiting(path, adjustment)

        val clue = s"Input path: [$path]; scaleFactor: [$scaleFactor]; bikeLaneLinkIds: [$bikeLaneLinkIds]"
        assert(result === 0, clue)
      }

      "pathScaledForWaiting scale disproportionately linkIds that are specified on BikeLaneAdjustment" in {
        val value1 = 11.4d
        val value2 = 12.3d
        val sharedLinkId1 = 2
        val nonSharedLinkId = 3
        val linkIds = Seq(1, sharedLinkId1, nonSharedLinkId)
        val path = randomPath(travelTimes = Seq(10d, value1, value2), linkIds)
        val data = new BikeLanesData(
          scaleFactorFromConfig = 2d,
          bikeLanesLinkIds = Set(sharedLinkId1, nonSharedLinkId + 1)
        )
        val adjustment = new BikeLanesAdjustment(data)

        val result: Double = ModeChoiceMultinomialLogit.pathScaledForWaiting(path, adjustment)

        val clue = s"Input path: [$path]"
        assert(result === value1 * 1 / data.scaleFactorFromConfig + value2, clue)
      }

    }

    "computeAllDayUtility" should {
      "override cost with expectedCost and scaledTime with travelTimeRatio when overrideAttributes is true" in {
        val config = testConfig("test/input/beamville/beam.conf").resolve()
        val beamConfig = BeamConfig(config)
        val beamConfigHolder = new BeamConfigHolder(mock(classOf[BeamConfigChangesObservable]), beamConfig)
        val (model, modeModel) = ModeChoiceMultinomialLogit.buildModelFromConfig(beamConfigHolder)

        val beamServices = mock(classOf[BeamServices])
        val beamScenario = mock(classOf[BeamScenario])
        val modeIncentives = ModeIncentive(Map.empty[BeamMode, List[ModeIncentive.Incentive]])
        when(beamServices.beamScenario).thenReturn(beamScenario)
        when(beamScenario.modeIncentives).thenReturn(modeIncentives)
        when(beamServices.beamConfig).thenReturn(beamConfig)
        val bikeLanesAdjustment = new BikeLanesAdjustment(new BikeLanesData(1.0, Set.empty))
        when(beamServices.bikeLanesAdjustment).thenReturn(bikeLanesAdjustment)

        val transitCrowding = mock(classOf[TransitCrowdingSkims])
        when(transitCrowding.getTransitOccupancyLevelForPercentile(any(), any())).thenReturn(0.0)
        val eventsManager = mock(classOf[EventsManager])

        val logit = new ModeChoiceMultinomialLogit(
          beamServices = beamServices,
          model = model,
          modeModel = modeModel,
          transitVehicleTypeVOTMultipliers = Map.empty,
          beamConfigHolder = beamConfigHolder,
          transitCrowding = transitCrowding,
          eventsManager = eventsManager
        )

        val person = PopulationUtils.getFactory.createPerson(Id.createPersonId("p1"))
        val attributesOfIndividual = AttributesOfIndividual(
          householdAttributes = HouseholdAttributes.EMPTY,
          modalityStyle = None,
          isMale = false,
          availableModes = Seq(BeamMode.WALK),
          rideHailServiceSubscription = Seq.empty,
          valueOfTime = 10.0,
          age = None,
          income = None
        )

        val dummyBeamLeg = BeamLeg(
          startTime = 3600,
          mode = BeamMode.WALK,
          duration = 1800,
          travelPath = BeamPath.empty
        )
        val dummyEmbodiedLeg = EmbodiedBeamLeg(
          beamLeg = dummyBeamLeg,
          beamVehicleId = Id.createVehicleId("walk_veh"),
          beamVehicleTypeId = Id.create("WALK", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 12.0,
          unbecomeDriverOnCompletion = true
        )
        val trip = EmbodiedBeamTrip(
          legs = Vector(dummyEmbodiedLeg)
        )

        // 1. Without overrides: cost = 12.0
        val normalUtility = logit.computeAllDayUtility(
          trips = Map(trip -> Map.empty[String, Double]),
          person = person,
          attributesOfIndividual = attributesOfIndividual,
          overrideAttributes = false
        )

        // 2. With overrides: lower expectedCost = 2.0 (utility should be higher)
        val overriddenUtility = logit.computeAllDayUtility(
          trips = Map(trip -> Map("expectedCost" -> 2.0, "travelTimeRatio" -> 1.0)),
          person = person,
          attributesOfIndividual = attributesOfIndividual,
          overrideAttributes = true
        )
        overriddenUtility should be > normalUtility

        // 3. With travelTimeRatio = 2.0 (faster expected time), utility should be even higher
        val fasterTimeUtility = logit.computeAllDayUtility(
          trips = Map(trip -> Map("expectedCost" -> 2.0, "travelTimeRatio" -> 2.0)),
          person = person,
          attributesOfIndividual = attributesOfIndividual,
          overrideAttributes = true
        )
        fasterTimeUtility should be > overriddenUtility

        // 4. When expectedCost is NaN, falls back to original cost
        val nanCostUtility = logit.computeAllDayUtility(
          trips = Map(trip -> Map("expectedCost" -> Double.NaN, "travelTimeRatio" -> 1.0)),
          person = person,
          attributesOfIndividual = attributesOfIndividual,
          overrideAttributes = true
        )
        nanCostUtility shouldBe normalUtility

        // 5. When travelTimeRatio <= 0.0, ignores invalid ratio and keeps original scaled time
        val negativeRatioUtility = logit.computeAllDayUtility(
          trips = Map(trip -> Map("expectedCost" -> 12.0, "travelTimeRatio" -> -1.0)),
          person = person,
          attributesOfIndividual = attributesOfIndividual,
          overrideAttributes = true
        )
        negativeRatioUtility shouldBe normalUtility
      }
    }
  }

}

object ModeChoiceMultinomialLogitSpec {

  def randomPath(travelTimes: Double*): BeamPath = {
    val linkIds = List.tabulate(travelTimes.size)(_ => Random.nextInt()).toIndexedSeq
    randomPath(travelTimes, linkIds)
  }

  def randomPath(travelTimes: Seq[Double], linkIds: Seq[Int]): BeamPath = {
    val tailSum = if (travelTimes.isEmpty) 0 else travelTimes.tail.sum
    val startPointTime = randomSpaceTime
    val newTime = (tailSum + startPointTime.time - Random.nextInt(2)).toInt
    BeamPath(
      linkIds = linkIds.toArray,
      linkTravelTime = travelTimes.toArray,
      transitStops = randomOption(randomTransitStops),
      startPoint = startPointTime,
      endPoint = randomSpaceTime.copy(time = newTime),
      distanceInM = Random.nextDouble()
    )
  }

  private def randomOption[T](value: => T): Option[T] = {
    if (Random.nextBoolean()) {
      Some(value)
    } else {
      None
    }
  }

  private def randomTransitStops: TransitStopsInfo = {
    val fromIdx = Random.nextInt(10)
    TransitStopsInfo(
      agencyId = randomShortString,
      routeId = randomShortString,
      vehicleId = Id.create(randomShortString, classOf[Vehicle]),
      fromIdx = fromIdx,
      toIdx = fromIdx + Random.nextInt(10)
    )
  }

  private def randomShortString: String = {
    val anyStringSizeUpperBound = 10
    Random.alphanumeric.take(Random.nextInt(anyStringSizeUpperBound)).mkString
  }

  private def randomSpaceTime: SpaceTime = {
    SpaceTime(
      x = Random.nextDouble(),
      y = Random.nextDouble(),
      time = Random.nextInt(24)
    )
  }

  private def randomIntList: Seq[Int] = {
    val anySizeUpperBound = 10
    List.tabulate(Random.nextInt(anySizeUpperBound))(_ => Random.nextInt())
  }

}
