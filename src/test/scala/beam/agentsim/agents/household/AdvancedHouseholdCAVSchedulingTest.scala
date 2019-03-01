package beam.agentsim.agents.household
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.agents.{Dropoff, Pickup}
import beam.router.BeamSkimmer
import org.matsim.api.core.v01.Id
import org.matsim.core.config.ConfigUtils
import org.matsim.core.scenario.ScenarioUtils
import org.matsim.households.Household
import org.scalatest.{FlatSpec, Matchers}



class AdvancedHouseholdCAVSchedulingTest extends FlatSpec with Matchers {

  behavior of "AdvancedHouseholdCAVScheduling"

  implicit val skim: BeamSkimmer = new BeamSkimmer()

  it should "generates two schedules" in {
    val config: org.matsim.core.config.Config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)
    implicit val pop: org.matsim.api.core.v01.population.Population = sc.getPopulation

    val cavs = List[BeamVehicle](
      new BeamVehicle(
        Id.createVehicleId("id1"),
        new Powertrain(0.0),
        HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
      )
    )
    val household: Household = HouseholdCAVSchedulingTest.scenario1(cavs)

    val alg = new AdvancedHouseholdCAVScheduling(household, cavs, Map((Pickup, 2), (Dropoff, 2)))
    val schedules = alg.getAllFeasibleSchedules
    schedules should have length 2
    schedules foreach (_.schedulesMap(cavs.head) should (have length 1 or have length 6))
    println(s"*** scenario 1 *** ${schedules.size} combinations")
  }

  it should "pool two persons for both trips" in {
    val config: org.matsim.core.config.Config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)
    implicit val pop: org.matsim.api.core.v01.population.Population = sc.getPopulation

    val cavs = List[BeamVehicle](
      new BeamVehicle(
        Id.createVehicleId("id1"),
        new Powertrain(0.0),
        HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
      ),
      new BeamVehicle(Id.createVehicleId("id2"),
        new Powertrain(0.0),
        BeamVehicleType.defaultCarBeamVehicleType)
    )
    val household: Household = HouseholdCAVSchedulingTest.scenario2(cavs)
    val alg = new AdvancedHouseholdCAVScheduling(
      household,
      cavs,
      Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
      stopSearchAfterXSolutions = 5000
    )
    val schedules = alg.getAllFeasibleSchedules
    schedules should have length 4
    schedules foreach (_.schedulesMap(cavs.head) should (have length 1 or (have length 6 or have length 10)))
    println(s"*** scenario 2 *** ${schedules.size} combinations")
  }

  it should "pool both agents in different CAVs" in {
    val config: org.matsim.core.config.Config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)
    implicit val pop: org.matsim.api.core.v01.population.Population = sc.getPopulation

    val cavs = List[BeamVehicle](
      new BeamVehicle(
        Id.createVehicleId("id1"),
        new Powertrain(0.0),
        HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
      ),
      new BeamVehicle(
        Id.createVehicleId("id2"),
        new Powertrain(0.0),
        HouseholdCAVSchedulingTest.defaultCAVBeamVehicleType
      )
    )
    val household: Household = HouseholdCAVSchedulingTest.scenario5(cavs)

    val alg = new AdvancedHouseholdCAVScheduling(
      household,
      cavs,
      Map((Pickup, 60 * 60), (Dropoff, 60 * 60)),
      stopSearchAfterXSolutions = 5000
    )
    val schedules = alg.getAllFeasibleSchedules
    schedules should have length 4
    schedules foreach (_.schedulesMap(cavs.head) should (have length 1 or (have length 6 or have length 10)))
    println(s"*** scenario 5 *** ${schedules.size} combinations")
  }

  it should "be scalable" in {
    val config: org.matsim.core.config.Config = ConfigUtils.createConfig()
    implicit val sc: org.matsim.api.core.v01.Scenario =
      ScenarioUtils.createScenario(config)
    implicit val pop: org.matsim.api.core.v01.population.Population = sc.getPopulation

    var sum = 0
    var count = 0
    val t0 = System.nanoTime()
    for((household, vehicles) <- HouseholdCAVSchedulingTest.scenarioPerformance) {
      val alg =
        new AdvancedHouseholdCAVScheduling(
          household,
          vehicles,
          Map((Pickup, 15 * 60), (Dropoff, 15 * 60)),
          stopSearchAfterXSolutions = Int.MaxValue
        )
      val schedules = alg.getAllFeasibleSchedules
      sum += schedules.size
      count += 1
      //println(s"household [${household.getId}]: ${schedule.size}")
    }
    val t1 = System.nanoTime()
    val elapsed = ((t1 - t0)/1E9).toInt
    elapsed shouldBe < (60)
    println(s"*** scenario 6 *** ${sum/count} avg combinations per household, ${elapsed} sec elapsed ")
  }


}
