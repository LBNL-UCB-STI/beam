package beam.replanning.utilitybased

import java.util
import java.util.Collections

import beam.agentsim.agents.memberships.HouseholdMembershipAllocator
import beam.sim.BeamHelper
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.{Household, HouseholdImpl, Households, HouseholdsImpl}
import org.matsim.vehicles.Vehicle
import org.scalatest.mockito.MockitoSugar
import org.scalatest.{BeforeAndAfterAll, FlatSpec, GivenWhenThen, Matchers}

class ChainBasedTourAllocatorSpec extends FlatSpec with Matchers with BeamHelper with MockitoSugar with GivenWhenThen with BeforeAndAfterAll {

  private var chainBasedTourVehicleAllocator: ChainBasedTourVehicleAllocator = _
  private var householdMembershipAllocator: HouseholdMembershipAllocator = _
  private val population = ScenarioUtils.createScenario(ConfigUtils.createConfig).getPopulation
  private val popFact = population.getFactory


  override def beforeAll(): Unit = {

  }

  "A ChainBasedTourVehicleAllocator" should "allocate a requested vehicle to an agent if it is available" in {
    Given("a person that is a member of a household")

    And("the person is of any rank")

    And("there are enough tour-based vehicles in the household")

    When("the person requests available vehicles")

    Then("allocate a tour-based vehicle.")

    it should not ""
  }

  private def createHouseholdWithNoConflict: Households = {
    val hhs: HouseholdsImpl = new HouseholdsImpl

    var hh: HouseholdImpl = hhs.getFactory.createHousehold(Id.create("small", classOf[Household])).asInstanceOf[HouseholdImpl]

    (1 to 5L).foreach(i => {
      hh.setMemberIds(util.Arrays.asList[Id[Person]](Id.createPersonId(i)))
      population.getPersonAttributes.putAttribute(s"$i","rank",i)
    })
    hh.setVehicleIds(Collections.emptyList[Id[Vehicle]])
    hhs.addHousehold(hh)

    hh = hhs.getFactory.createHousehold(Id.create("big", classOf[Household])).asInstanceOf[HouseholdImpl]
    (2 to 10L).foreach(i=>hh.setMemberIds(util.Arrays.asList[Id[Person]](Id.createPersonId(i))))
    hh.setVehicleIds(util.Arrays.asList[Id[Vehicle]](Id.createVehicleId(1)))
    hhs.addHousehold(hh)

    hh = hhs.getFactory.createHousehold(Id.create("lots of vehicles", classOf[Household])).asInstanceOf[HouseholdImpl]
    (10 to 15L).foreach(i => hh.setMemberIds(util.Arrays.asList[Id[Person]](Id.createPersonId(i))))
    (2 to 10L).foreach(i => hh.setVehicleIds(util.Arrays.asList[Id[Vehicle]](Id.createVehicleId(i))))
    hhs.addHousehold(hh)

    hhs
  }

}
