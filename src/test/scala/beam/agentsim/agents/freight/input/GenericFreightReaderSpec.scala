package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight._
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
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
class GenericFreightReaderSpec extends AnyWordSpecLike with Matchers {
  private val freightInputDir = s"${System.getenv("PWD")}/test/test-resources/beam/agentsim/freight"
  private val tazMap: TAZTreeMap = TAZTreeMap.fromCsv("test/input/beamville/taz-centers.csv")

  private val geoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  private val freightConfig: Freight = new Freight(
    carrierParkingFilePath = None,
    carriersFilePath = s"$freightInputDir/freight-carriers.csv",
    plansFilePath = s"$freightInputDir/payload-plans.csv",
    toursFilePath = s"$freightInputDir/freight-tours.csv",
    convertWgs2Utm = false,
    enabled = true,
    name = "Freight",
    reader = "Generic",
    replanning = new Freight.Replanning(departureTime = 0, disableAfterIteration = -1, strategy = ""),
    generateFixedActivitiesDurations = false
  )

  val rnd = new Random(2333L)

  private val reader =
    new GenericFreightReader(freightConfig, geoUtils, rnd, tazMap, snapLocationAndRemoveInvalidInputs = false)

  "PayloadPlansConverter" should {
    "read Payload Plans" in {
      val payloadPlans: Map[Id[PayloadPlan], PayloadPlan] = reader.readPayloadPlans()
      payloadPlans should have size 8
      val plan7 = payloadPlans("payload-7".createId)
      plan7.payloadId should be("payload-7".createId)
      plan7.locationUTM should be(new Coord(169624.51213105154, 3272.492326224974))
      plan7.estimatedTimeOfArrivalInSec should be(18000)
      plan7.arrivalTimeWindowInSecLower should be(1800)
      plan7.operationDurationInSec should be(500)
      plan7.sequenceRank should be(3)
      plan7.tourId should be("tour-3".createId[FreightTour])
      plan7.payloadType should be("goods".createId[PayloadType])
      plan7.weightInKg should be(1500)
      plan7.requestType should be(FreightRequestType.Loading)
    }

    "read Freight Tours" in {
      val tours = reader.readFreightTours()
      tours should have size 4
      val tour3 = tours("tour-3".createId)
      tour3.tourId should be("tour-3".createId)
      tour3.departureTimeInSec should be(15000)
      tour3.maxTourDurationInSec should be(36000)
    }

    "readFreightCarriers" in {
      val freightCarriers: scala.IndexedSeq[FreightCarrier] = readCarriers
      freightCarriers should have size 2
      val result = freightCarriers.find(_.carrierId == "freight-carrier-1".createId[FreightCarrier])
      result should be('defined)
      val carrier1 = result.get
      carrier1.fleet should have size 2
      carrier1.payloadPlans should have size 7
      carrier1.tourMap should have size 2
      carrier1.tourMap should contain key Id.createVehicleId("freight-vehicle-2")
      carrier1.tourMap(Id.createVehicleId("freight-vehicle-2")) should have size 1
      carrier1.tourMap(Id.createVehicleId("freight-vehicle-2")).head should have(
        'tourId ("tour-1".createId[FreightTour]),
        'departureTimeInSec (1000),
        'maxTourDurationInSec (36000)
      )
      carrier1.plansPerTour should have size 3
      carrier1.plansPerTour("tour-1".createId) should have size 2
      carrier1.plansPerTour("tour-2".createId) should have size 2
      carrier1.plansPerTour("tour-3".createId) should have size 3

      val result2 = freightCarriers.find(_.carrierId == "freight-carrier-2".createId[FreightCarrier])
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

      reader.generatePopulation(
        readCarriers,
        populationFactory,
        householdFactory
      )

      personPlans should have size 3
      val plan1 = personPlans(Id.createPersonId("freight-carrier-1-vehicle-1-agent"))
      plan1.getPlanElements should have size 15
      plan1.getPlanElements.get(2).asInstanceOf[Activity].getCoord should be(
        new Coord(169567.3017564815, 836.6518909569604)
      )
      plan1.getPlanElements.get(12).asInstanceOf[Activity].getCoord should be(
        new Coord(169576.80444138843, 3380.0075111142937)
      )
      val plan4 = personPlans(Id.createPersonId("freight-carrier-2-vehicle-3-agent"))
      plan4.getPlanElements should have size 5
      plan4.getPlanElements.get(2).asInstanceOf[Activity].getCoord should be(
        new Coord(169900.11498160253, 3510.2356380579545)
      )
      plan4.getPlanElements.get(4).asInstanceOf[Activity].getType should be("Warehouse")
    }
  }

  private def readCarriers: IndexedSeq[FreightCarrier] = {
    val converter = new GenericFreightReader(
      freightConfig,
      geoUtils,
      new Random(4324L),
      tazMap,
      snapLocationAndRemoveInvalidInputs = false
    )
    val payloadPlans: Map[Id[PayloadPlan], PayloadPlan] = converter.readPayloadPlans()
    val tours = converter.readFreightTours()
    val vehicleTypes = BeamVehicleUtils.readBeamVehicleTypeFile("test/input/beamville/vehicleTypes.csv")
    val freightCarriers: IndexedSeq[FreightCarrier] =
      new GenericFreightReader(
        freightConfig,
        geoUtils,
        new Random(73737L),
        tazMap,
        snapLocationAndRemoveInvalidInputs = false
      ).readFreightCarriers(
        tours,
        payloadPlans,
        vehicleTypes
      )
    freightCarriers
  }
}
