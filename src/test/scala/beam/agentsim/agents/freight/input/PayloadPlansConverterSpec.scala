package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight._
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.utils.BeamVehicleUtils
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.population.{Activity, Person, Plan, PopulationFactory}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.{Household, HouseholdImpl, HouseholdsFactory}
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.util
import scala.collection.mutable
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class PayloadPlansConverterSpec extends AnyWordSpecLike with Matchers {
  private val freightInputDir = s"${System.getenv("PWD")}/test/test-resources/beam/agentsim/freight"
  private val tazMap: TAZTreeMap = TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")

  "PayloadPlansConverter" should {
    "read Payload Plans" in {
      val payloadPlans: Map[Id[PayloadPlan], PayloadPlan] =
        PayloadPlansConverter.readPayloadPlans(s"$freightInputDir/payload-plans.csv", tazMap, new Random(2333L))
      payloadPlans should have size 8
      val plan7 = payloadPlans("payload-7".createId)
      plan7.payloadId should be("payload-7".createId)
      plan7.location should be(new Coord(169624.51213105154, 3272.492326224974))
      plan7.estimatedTimeOfArrivalInSec should be(18000)
      plan7.arrivalTimeWindowInSec should be(1800)
      plan7.operationDurationInSec should be(500)
      plan7.sequenceRank should be(3)
      plan7.tourId should be("tour-3".createId[FreightTour])
      plan7.payloadType should be("goods".createId[PayloadType])
      plan7.weight should be(1500)
      plan7.requestType should be(FreightRequestType.Loading)
    }

    "read Freight Tours" in {
      val tours = PayloadPlansConverter.readFreightTours(s"$freightInputDir/freight-tours.csv")
      tours should have size 4
      val tour3 = tours("tour-3".createId)
      tour3.tourId should be("tour-3".createId)
      tour3.departureTimeInSec should be(15000)
      tour3.warehouseLocation should be(new Coord(170308.4, 2964.6))
      tour3.maxTourDurationInSec should be(36000)
    }

    "readFreightCarriers" in {
      val freightCarriers: scala.IndexedSeq[FreightCarrier] = readCarriers
      freightCarriers should have size 2
      val result = freightCarriers.find(_.carrierId == "carrier-1".createId[FreightCarrier])
      result should be('defined)
      val carrier1 = result.get
      carrier1.fleet should have size 2
      carrier1.payloadPlans should have size 7
      carrier1.tourMap should have size 2
      carrier1.tourMap should contain key Id.createVehicleId("freight-2")
      carrier1.tourMap(Id.createVehicleId("freight-2")) should have size 1
      carrier1.tourMap(Id.createVehicleId("freight-2")).head should have(
        'tourId ("tour-1".createId[FreightTour]),
        'departureTimeInSec (1000),
        'warehouseLocation (new Coord(169637.3661199976, 3030.52756066406)),
        'maxTourDurationInSec (36000)
      )
      carrier1.plansPerTour should have size 3
      carrier1.plansPerTour("tour-1".createId) should have size 2
      carrier1.plansPerTour("tour-2".createId) should have size 2
      carrier1.plansPerTour("tour-3".createId) should have size 3

      val result2 = freightCarriers.find(_.carrierId == "carrier-2".createId[FreightCarrier])
      result2 should be('defined)
      val carrier2 = result2.get
      carrier2.fleet should have size 1
      carrier2.payloadPlans should have size 1
      carrier2.tourMap should have size 1
      carrier2.plansPerTour should have size 1
    }

    "generate Population" in {
      val personPlans = mutable.Map.empty[Id[Person], Plan]

      val populationFactory: PopulationFactory = Mockito.mock(classOf[PopulationFactory])
      when(populationFactory.createPerson(any())).thenAnswer { invocation =>
        val personId = invocation.getArgument[Id[Person]](0)
        val person = Mockito.mock(classOf[Person])
        when(person.addPlan(any())).thenAnswer { invocation =>
          val plan: Plan = invocation.getArgument(0)
          personPlans += (personId -> plan)
          true
        }
        person
      }

      val householdFactory: HouseholdsFactory = Mockito.mock(classOf[HouseholdsFactory])
      when(householdFactory.createHousehold(any())).thenAnswer { invocation =>
        val id = invocation.getArgument[Id[Household]](0)
        val household = new HouseholdImpl(id)
        household.setMemberIds(new util.ArrayList())
        household.setVehicleIds(new util.ArrayList())
        household
      }

      PayloadPlansConverter.generatePopulation(
        readCarriers,
        populationFactory,
        householdFactory,
        None
      )

      personPlans should have size 3
      val plan1 = personPlans(Id.createPersonId("freight-agent-freight-1"))
      plan1.getPlanElements should have size 15
      plan1.getPlanElements.get(2).asInstanceOf[Activity].getCoord should be(
        new Coord(169567.3017564815, 836.6518909569604)
      )
      plan1.getPlanElements.get(12).asInstanceOf[Activity].getCoord should be(
        new Coord(169576.80444138843, 3380.0075111142937)
      )
      val plan4 = personPlans(Id.createPersonId("freight-agent-freight-2-1"))
      plan4.getPlanElements should have size 5
      plan4.getPlanElements.get(2).asInstanceOf[Activity].getCoord should be(
        new Coord(169900.11498160253, 3510.2356380579545)
      )
      plan4.getPlanElements.get(4).asInstanceOf[Activity].getType should be("Warehouse")
    }
  }

  private def readCarriers: IndexedSeq[FreightCarrier] = {
    val payloadPlans: Map[Id[PayloadPlan], PayloadPlan] =
      PayloadPlansConverter.readPayloadPlans(s"$freightInputDir/payload-plans.csv", tazMap, new Random(4324L))
    val tours = PayloadPlansConverter.readFreightTours(s"$freightInputDir/freight-tours.csv")
    val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")
    val freightCarriers: IndexedSeq[FreightCarrier] = PayloadPlansConverter.readFreightCarriers(
      s"$freightInputDir/freight-carriers.csv",
      tours,
      payloadPlans,
      vehicleTypes,
      tazMap,
      new Random(73737L)
    )
    freightCarriers
  }
}
