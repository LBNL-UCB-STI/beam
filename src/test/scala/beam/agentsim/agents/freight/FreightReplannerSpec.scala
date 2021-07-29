package beam.agentsim.agents.freight

import beam.jspritwrapper.{Dropoff, Pickup}
import beam.sim.BeamServices
import beam.utils.TestConfigUtils
import beam.utils.TestConfigUtils.testConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Activity
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class FreightReplannerSpec extends AnyWordSpecLike with Matchers {

  "FreightReplanner" should {
    "solve with singleVehicle strategy" in {
      val beamServices: BeamServices = TestConfigUtils.configToBeamServices(
        TestConfigUtils.testConfig("test/input/beamville/beam-freight.conf").resolve()
      )

      val replanner = new FreightReplanner(beamServices, beamServices.skims.od_skimmer, new Random(100))
      val carrier =
        beamServices.beamScenario.freightCarriers.find(_.carrierId == "carrier-1".createId[FreightCarrier]).get
      val routes = replanner.calculateRoutes(carrier, "singleTour", 0)
      routes should have size 3
      routes(0).vehicle.id should be("freight-1")
      routes(0).startTime should be(1000)
      routes(0).activities should have size 2
      routes(0).activities(0).service.id should be("payload-1")
      routes(0).activities(0).service shouldBe a[Dropoff]
      routes(0).activities(1).service.id should be("payload-2")
      routes(0).activities(1).service shouldBe a[Pickup]
      routes(1).vehicle.id should be("freight-1")
      routes(1).startTime should be(7000)
      routes(1).activities should have size 2
      routes(1).activities(0).service.id should be("payload-4")
      routes(1).activities(0).service shouldBe a[Dropoff]
      routes(1).activities(1).service.id should be("payload-3")
      routes(1).activities(1).service shouldBe a[Dropoff]
      routes(2).vehicle.id should be("freight-1")
      routes(2).startTime should be(15000)
      routes(2).activities should have size 3
      routes(2).activities(0).service.id should be("payload-6")
      routes(2).activities(0).service shouldBe a[Dropoff]
      routes(2).activities(1).service.id should be("payload-7")
      routes(2).activities(1).service shouldBe a[Dropoff]
      routes(2).activities(2).service.id should be("payload-5")
      routes(2).activities(2).service shouldBe a[Pickup]
    }

    "solve with wholeFleet strategy for a single vehicle" in {
      val beamServices: BeamServices = TestConfigUtils.configToBeamServices(
        TestConfigUtils.testConfig("test/input/beamville/beam-freight.conf").resolve()
      )

      //jsprit doesn't support multiple tours for a single vehicle. Because the first carrier has a single vehicle
      //then a single tour is returned

      val replanner = new FreightReplanner(beamServices, beamServices.skims.od_skimmer, new Random(100))
      val carrier =
        beamServices.beamScenario.freightCarriers.find(_.carrierId == "carrier-1".createId[FreightCarrier]).get
      val routes = replanner.calculateRoutes(carrier, "wholeFleet", 0)
      routes should have size 1
      routes(0).vehicle.id should be("freight-1")
      routes(0).startTime should be(5515)
      routes(0).activities should have size 3
      routes(0).activities(0).service.id should be("payload-3")
      routes(0).activities(0).service shouldBe a[Dropoff]
      routes(0).activities(1).service.id should be("payload-4")
      routes(0).activities(1).service shouldBe a[Dropoff]
      routes(0).activities(2).service.id should be("payload-2")
      routes(0).activities(2).service shouldBe a[Pickup]
    }

    "solve with wholeFleet strategy for multiple vehicles" in {
      val beamServices: BeamServices = TestConfigUtils.configToBeamServices(
        TestConfigUtils.testConfig("test/input/beamville/beam-freight.conf").resolve()
      )

      val replanner = new FreightReplanner(beamServices, beamServices.skims.od_skimmer, new Random(100))
      val carrier =
        beamServices.beamScenario.freightCarriers.find(_.carrierId == "carrier-2".createId[FreightCarrier]).get
      val routes = replanner.calculateRoutes(carrier, "wholeFleet", 0)
      routes should have size 2
      routes(0).vehicle.id should be("freight-3")
      routes(0).startTime should be(5050)
      routes(0).activities should have size 3
      routes(0).activities(0).service.id should be("payload-9")
      routes(0).activities(0).service shouldBe a[Dropoff]
      routes(0).activities(1).service.id should be("payload-11")
      routes(0).activities(1).service shouldBe a[Dropoff]
      routes(0).activities(2).service.id should be("payload-10")
      routes(0).activities(2).service shouldBe a[Pickup]
      routes(1).vehicle.id should be("freight-2")
      routes(1).startTime should be(5515)
      routes(1).activities should have size 1
      routes(1).activities(0).service.id should be("payload-8")
      routes(1).activities(0).service shouldBe a[Pickup]
    }

    "replan person plans" in {
      val beamServices: BeamServices = TestConfigUtils.configToBeamServices(
        ConfigFactory
          .parseString(
            """
            beam.agentsim.agents.freight.replanning.departureTime = 10000
            beam.agentsim.agents.freight.replanning.strategy = wholeFleet
            """
          )
          .withFallback(testConfig("test/input/beamville/beam-freight.conf").resolve())
      )

      val replanner = new FreightReplanner(beamServices, beamServices.skims.od_skimmer, new Random(100))
      val carrier =
        beamServices.beamScenario.freightCarriers.find(_.carrierId == "carrier-1".createId[FreightCarrier]).get
      replanner.replan(carrier)
      val person = beamServices.matsimServices.getScenario.getPopulation.getPersons
        .get(Id.createPersonId("freight-agent-freight-1"))
      val plan = person.getSelectedPlan
      plan.getPlanElements should have size 9
      plan.getPlanElements.get(0) shouldBe a[Activity]
      val activity0 = plan.getPlanElements.get(0).asInstanceOf[Activity]
      activity0.getType should be("Warehouse")
      activity0.getEndTime should be(11915.0)
      plan.getPlanElements.get(2).asInstanceOf[Activity].getType should be("Unloading")
      plan.getPlanElements.get(4).asInstanceOf[Activity].getType should be("Unloading")
      plan.getPlanElements.get(6).asInstanceOf[Activity].getType should be("Loading")
      plan.getPlanElements.get(8).asInstanceOf[Activity].getType should be("Warehouse")
    }

  }
}
