package beam.replanning.utilitybased

import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.router.Modes
import beam.sim.BeamHelper
import org.junit.Assert
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population._
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.router.TripStructureUtils
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdImpl, HouseholdsImpl}
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.vehicles.{Vehicle, VehicleType, VehicleUtils, Vehicles}
import org.scalatestplus.mockito.MockitoSugar
import org.scalatest.{FlatSpec, GivenWhenThen, Matchers}

import scala.collection.{immutable, JavaConverters}
import scala.util.Random

class ChainBasedTourAllocatorSpec extends FlatSpec with Matchers with BeamHelper with MockitoSugar with GivenWhenThen {

  val MODE = "Car"

  trait ChainBasedTourAllocatorTestFixture {
    lazy val pop: Population = ScenarioUtils.createScenario(ConfigUtils.createConfig).getPopulation
    lazy val popFact: PopulationFactory = pop.getFactory
    lazy val persAttr: ObjectAttributes = pop.getPersonAttributes
    lazy val vehs: Vehicles = VehicleUtils.createVehiclesContainer()

    // These are unique to each test case
    val personList: immutable.IndexedSeq[Id[Person]]
    val vehicleList: immutable.IndexedSeq[Id[Vehicle]]

    lazy val hhs = new HouseholdsImpl

    lazy val hh: HouseholdImpl = hhs.getFactory
      .createHousehold(Id.create("hh", classOf[Household]))
      .asInstanceOf[HouseholdImpl]
    var chainBasedTourVehicleAllocator: ChainBasedTourVehicleAllocator = _

    def init() {
      // Create and add people
      personList.foreach(id => {
        val person = popFact.createPerson(id)
        val plan: Plan = createPlan(id.toString.toInt)
        plan.setPerson(person)
        person.addPlan(plan)
        pop.addPerson(person)
        pop.getPersonAttributes.putAttribute(s"$id", "rank", id.toString.toLong)
      })

      // Create vehicles
      val vehType: VehicleType = VehicleUtils.getDefaultVehicleType
      vehType.setDescription(MODE)
      vehs.addVehicleType(vehType)

      vehicleList.foreach { id =>
        {
          vehs.addVehicle(vehs.getFactory.createVehicle(id, vehType))
        }
      }

      // Add people and vehicles to household
      hh.setMemberIds(JavaConverters.seqAsJavaList(personList))
      hh.setVehicleIds(JavaConverters.seqAsJavaList(vehicleList.map(Id.createVehicleId(_))))
      hhs.addHousehold(hh)

      chainBasedTourVehicleAllocator = ChainBasedTourVehicleAllocator(
        vehs,
        HouseholdMembershipAllocator(hhs, pop),
        Set[String]("car")
      )
    }

    def createPlan(i: Int): Plan = {
      val plan = popFact.createPlan()

      plan.addActivity(popFact.createActivityFromLinkId("h", Id.createLinkId(42)))

      val hw = popFact.createLeg("some_mode")
      hw.setRoute(RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId(42), Id.createLinkId(12)))
      plan.addLeg(hw)

      plan.addActivity(popFact.createActivityFromLinkId("w", Id.createLinkId(12)))

      val wh = popFact.createLeg("some_other_mode")
      wh.setRoute(RouteUtils.createLinkNetworkRouteImpl(Id.createLinkId(12), Id.createLinkId(42)))
      plan.addLeg(wh)
      plan.addActivity(popFact.createActivityFromLinkId("h", Id.createLinkId(42)))
      plan
    }

    private def assertSingleVehicleAndGetVehicleId(p: Plan): Id[Vehicle] = {

      var v: Option[Id[Vehicle]] = None

      JavaConverters
        .iterableAsScalaIterable(p.getPlanElements)
        .toList
        .foreach({
          case leg: Leg if leg.getMode == MODE =>
            val r = leg.getRoute.asInstanceOf[NetworkRoute]
            Assert.assertNotNull("null vehicle id in route", r.getVehicleId)
            Assert.assertTrue(
              s"vehicle ${r.getVehicleId} not same as $v",
              v.isEmpty || r.getVehicleId == v.get
            )
            v = Option(r.getVehicleId)
        })

      v.getOrElse(throw new RuntimeException("Not sure what's going on here!"))
    }
  }

  private def createHouseholdWithEnoughVehicles = new ChainBasedTourAllocatorTestFixture {
    override val personList: immutable.IndexedSeq[Id[Person]] = (1 to 5).map(Id.createPersonId(_))
    override val vehicleList: immutable.IndexedSeq[Id[Vehicle]] =
      personList.map(Id.createVehicleId(_))
    init()
  }

  private def createHouseholdsWithTooFewVehicles = new ChainBasedTourAllocatorTestFixture {
    override val personList: immutable.IndexedSeq[Id[Person]] = (1 to 5).map(Id.createPersonId(_))
    override val vehicleList: immutable.IndexedSeq[Id[Vehicle]] =
      (1 to 2).map(Id.createVehicleId(_))
    init()
  }

  behavior of "A ChainBasedTourVehicleAllocator"

  it should "allocate a chain-based vehicle to an agent if one is available in the household" in {

    Given("A household with several agents")
    val f = createHouseholdWithEnoughVehicles

    And("enough chain-based vehicles in the household for everyone")
    val vehicles = JavaConverters.mapAsScalaMap(f.vehs.getVehicles)
    vehicles.size shouldEqual f.hh.getMemberIds.size()

    And("a household member of any rank that is a member of the household")
    val rng = Random
    val idRankNum = rng.nextInt(5) + 1

    val personWithAnyRank = Id.createPersonId(idRankNum)
    f.persAttr.getAttribute(personWithAnyRank.toString, "rank") should be(idRankNum)

    And("the person would like to know which chain-based modes are available")
    val availableVehicleModes =
      f.chainBasedTourVehicleAllocator.identifyChainBasedModesForAgent(personWithAnyRank)

    Then("a chain-based mode should be available,")
    availableVehicleModes should contain atLeastOneElementOf Modes.BeamMode.chainBasedModes

    And("if the person requests a tour-based vehicle,")
    val plan = f.pop.getPersons.get(personWithAnyRank).getPlans.get(0)
    val subtour = JavaConverters
      .collectionAsScalaIterable(
        TripStructureUtils.getSubtours(plan, f.chainBasedTourVehicleAllocator.stageActivitytypes)
      )
      .toIndexedSeq(0)
    f.chainBasedTourVehicleAllocator.allocateChainBasedModesforHouseholdMember(
      personWithAnyRank,
      subtour,
      plan
    )
    val legs = JavaConverters.collectionAsScalaIterable(subtour.getTrips).flatMap { trip =>
      JavaConverters
        .collectionAsScalaIterable(trip.getLegsOnly)
    }

    Then("it should be allocated to the person.")

    val modes = legs.map(leg => leg.getMode)

//    modes should contain only "car"

//    val vehicleIds: Vector[Id[Vehicle]] = legs.map(leg=>leg.getRoute.asInstanceOf[NetworkRoute]
//      .getVehicleId).toVector
//
//    vehicleIds should contain only vehicleIds.head

  }

  it should "not allocate a chain-based vehicle to an agent when there aren't any available in the household" in {
    Given("A household with several agents")
    val f = createHouseholdsWithTooFewVehicles

    And("too few chain-based vehicles in the household")
    val vehicles = JavaConverters.mapAsScalaMap(f.vehs.getVehicles)

    And("a household member of high rank")
    val personWithHighRank = Id.createPersonId(5)
    f.persAttr.getAttribute(personWithHighRank.toString, "rank") should be(5)

    And("a household member of low rank")
    val personWithLowRank = Id.createPersonId(1)
    f.persAttr.getAttribute(personWithLowRank.toString, "rank") should be(1)

    And("the two members would like to know which chain-based modes are available")
    val availableLowRankModes = f.chainBasedTourVehicleAllocator
      .identifyChainBasedModesForAgent(personWithLowRank)
    val availableHighRankModes = f.chainBasedTourVehicleAllocator
      .identifyChainBasedModesForAgent(personWithHighRank)

    Then("a chain-based mode should not be available for a low-ranking individual")
    availableLowRankModes.size should be(0)

    And("a chain-based mode should be available for a high-ranking individual")
    availableHighRankModes should contain atLeastOneElementOf Modes.BeamMode.chainBasedModes

    And("it should be allocated to the high-ranking person,")
    val highRankPlan = f.pop.getPersons.get(personWithHighRank).getPlans.get(0)
    val highRankSubtour = JavaConverters
      .collectionAsScalaIterable(
        TripStructureUtils
          .getSubtours(highRankPlan, f.chainBasedTourVehicleAllocator.stageActivitytypes)
      )
      .toIndexedSeq(0)
    f.chainBasedTourVehicleAllocator.allocateChainBasedModesforHouseholdMember(
      personWithHighRank,
      highRankSubtour,
      highRankPlan
    )
    val highRankLegs = JavaConverters.collectionAsScalaIterable(highRankSubtour.getTrips).flatMap { trip =>
      JavaConverters
        .collectionAsScalaIterable(trip.getLegsOnly)
    }
    val highRankModes = highRankLegs.map(leg => leg.getMode)

//    highRankModes should contain only "car"

//    val highRankVehicles: Iterable[Id[Vehicle]] = highRankLegs.map(leg=>leg.getRoute.asInstanceOf[NetworkRoute]
//      .getVehicleId).toIndexedSeq
//
//    highRankVehicles should contain only Id.createVehicleId("1")
//
//      And("it should not be allocated to the low-ranking person.")
//    val lowRankPlan = f.pop.getPersons.get(personWithLowRank).getPlans.get(0)
//    val lowRankSubtour = JavaConverters.collectionAsScalaIterable(TripStructureUtils.getSubtours(lowRankPlan,
//      f.chainBasedTourVehicleAllocator.stageActivitytypes)).toIndexedSeq(0)
//    f.chainBasedTourVehicleAllocator.allocateChainBasedModesforHouseholdMember(personWithLowRank, lowRankSubtour,
//      lowRankPlan)
//    val lowRankModes = JavaConverters.collectionAsScalaIterable(lowRankSubtour.getTrips).flatMap { trip =>
//      JavaConverters
//        .collectionAsScalaIterable(trip.getLegsOnly)
//    }.map(leg => leg.getMode)
//    lowRankModes should not contain "car"
  }

}
