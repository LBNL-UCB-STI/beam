package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight.FreightRequestType.{Loading, Unloading}
import beam.agentsim.agents.freight.input.FreightReader.{FREIGHT_REQUEST_TYPE, PAYLOAD_WEIGHT_IN_KG}
import beam.agentsim.agents.freight.{FreightCarrier, FreightRequestType, FreightTour, PayloadPlan}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
import beam.utils.SnapCoordinateUtils.SnapLocationHelper
import com.conveyal.r5.streets.StreetLayer
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{Household, HouseholdsFactory, Income, IncomeImpl}
import org.matsim.vehicles.Vehicle

import java.util.concurrent.atomic.AtomicReference
import scala.util.Random

trait FreightReader {
  val geoUtils: GeoUtils
  val config: Freight

  def readFreightTours(): Map[Id[FreightTour], FreightTour]

  def readPayloadPlans(): Map[Id[PayloadPlan], PayloadPlan]

  def createPersonId(vehicleId: Id[BeamVehicle]): Id[Person]

  def createHouseholdId(vehicleId: Id[BeamVehicle]): Id[Household]

  def readFreightCarriers(
    allTours: Map[Id[FreightTour], FreightTour],
    allPlans: Map[Id[PayloadPlan], PayloadPlan],
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]
  ): IndexedSeq[FreightCarrier]

  def calculatePayloadWeights(plans: IndexedSeq[PayloadPlan]): IndexedSeq[Double] = {
    val initialWeight = 0.0
    plans.foldLeft(IndexedSeq(initialWeight)) {
      case (acc, PayloadPlan(_, _, _, _, weight, Unloading, _, _, _, _, _, _, _)) => acc :+ acc.last - weight
      case (acc, PayloadPlan(_, _, _, _, weight, Loading, _, _, _, _, _, _, _))   => acc :+ acc.last + weight
    }
  }

  def createPersonPlan(
    tours: IndexedSeq[FreightTour],
    plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]],
    person: Person
  ): Plan = {
    val allToursPlanElements = tours.flatMap { tour =>
      val tourInitialActivity =
        createFreightActivity("Warehouse", tour.warehouseLocationUTM, tour.departureTimeInSec, None)
      val firstLeg: Leg = createFreightLeg(tour.departureTimeInSec)

      val plans: IndexedSeq[PayloadPlan] = plansPerTour.get(tour.tourId) match {
        case Some(value) => value
        case None        => throw new IllegalArgumentException(s"Tour '${tour.tourId}' has no plans")
      }

      val planElements: IndexedSeq[PlanElement] = plans.flatMap { plan =>
        val activityEndTime = plan.estimatedTimeOfArrivalInSec + plan.operationDurationInSec
        val activityType = plan.activityType
        val activity = createFreightActivity(activityType, plan.locationUTM, activityEndTime, Some(plan.requestType))
        val leg: Leg = createFreightLeg(activityEndTime)
        Seq(activity, leg)
      }

      val elements = tourInitialActivity +: firstLeg +: planElements
      val weightsToCarry: IndexedSeq[Double] = calculatePayloadWeights(plans)
      elements
        .collect { case leg: Leg => leg }
        .zip(weightsToCarry)
        .foreach { case (leg, payloadWeight) =>
          leg.getAttributes.putAttribute(PAYLOAD_WEIGHT_IN_KG, payloadWeight)
        }
      elements
    }

    val finalActivity = createFreightActivity("Warehouse", tours.head.warehouseLocationUTM, -1, None)
    val allPlanElements: IndexedSeq[PlanElement] = allToursPlanElements :+ finalActivity

    val currentPlan = PopulationUtils.createPlan(person)
    allPlanElements.foreach {
      case activity: Activity => currentPlan.addActivity(activity)
      case leg: Leg           => currentPlan.addLeg(leg)
      case _                  => throw new UnknownError() //shouldn't happen
    }
    currentPlan
  }

  def generatePopulation(
    carriers: IndexedSeq[FreightCarrier],
    personFactory: PopulationFactory,
    householdsFactory: HouseholdsFactory
  ): IndexedSeq[(Household, Plan)] = {
    carriers.flatMap { carrier =>
      carrier.tourMap.map { case (vehicleId, tours) =>
        val personId = createPersonId(vehicleId)
        val person = personFactory.createPerson(personId)

        val currentPlan: Plan = createPersonPlan(tours, carrier.plansPerTour, person)

        person.addPlan(currentPlan)
        person.setSelectedPlan(currentPlan)

        val freightHouseholdId = createHouseholdId(vehicleId)
        val household: Household = householdsFactory.createHousehold(freightHouseholdId)
        household.setIncome(new IncomeImpl(44444, Income.IncomePeriod.year))
        household.getMemberIds.add(personId)
        household.getVehicleIds.add(vehicleId)

        (household, currentPlan)
      }
    }
  }

  protected def createFreightVehicle(
    vehicleId: Id[Vehicle],
    vehicleType: BeamVehicleType,
    carrierId: Id[FreightCarrier],
    initialLocation: Coord,
    randomSeed: Int
  ): BeamVehicle = {
    val beamVehicleId = BeamVehicle.createId(vehicleId)

    val powertrain = Powertrain(Option(vehicleType.primaryFuelConsumptionInJoulePerMeter))

    val vehicle = new BeamVehicle(
      beamVehicleId,
      powertrain,
      vehicleType,
      vehicleManagerId = new AtomicReference(
        VehicleManager.createOrGetReservedFor(carrierId.toString, VehicleManager.TypeEnum.Freight).managerId
      ),
      randomSeed
    )
    vehicle.spaceTime = SpaceTime(initialLocation, 0)
    vehicle
  }

  protected def createFreightActivity(
    activityType: String,
    locationUTM: Coord,
    endTime: Int,
    freightRequestType: Option[FreightRequestType]
  ): Activity = {
    val act = PopulationUtils.createActivityFromCoord(activityType, locationUTM)
    if (endTime >= 0) {
      act.setEndTime(endTime)
    }
    freightRequestType.foreach(act.getAttributes.putAttribute(FREIGHT_REQUEST_TYPE, _))
    act
  }

  protected def createFreightLeg(departureTime: Int): Leg = {
    val leg = PopulationUtils.createLeg(BeamMode.CAR.value)
    leg.setDepartureTime(departureTime)
    leg
  }

  protected def location(x: Double, y: Double): Coord = convertedLocation(new Coord(x, y))

  protected def convertedLocation(coord: Coord): Coord = {
    if (config.convertWgs2Utm) {
      geoUtils.wgs2Utm(coord)
    } else {
      coord
    }
  }

}

object FreightReader {
  val FREIGHT_ID_PREFIX = "freight"
  val FREIGHT_REQUEST_TYPE = "FreightRequestType"
  val PAYLOAD_WEIGHT_IN_KG = "PayloadWeightInKg"

  def apply(
    beamConfig: BeamConfig,
    geoUtils: GeoUtils,
    streetLayer: StreetLayer,
    tazMap: TAZTreeMap,
    outputDirMaybe: Option[String]
  ): FreightReader = {
    val rand: Random = new Random(beamConfig.matsim.modules.global.randomSeed)
    val config = beamConfig.beam.agentsim.agents.freight
    val snapLocationHelper: SnapLocationHelper = SnapLocationHelper(
      geoUtils,
      streetLayer,
      beamConfig.beam.routing.r5.linkRadiusMeters
    )
    beamConfig.beam.agentsim.agents.freight.reader match {
      case "Generic" =>
        new GenericFreightReader(
          config,
          geoUtils,
          rand,
          tazMap,
          Some(snapLocationHelper),
          outputDirMaybe
        )
      case s =>
        throw new RuntimeException(s"Unknown freight reader $s")
    }
  }

  def apply(
    beamConfig: BeamConfig,
    geoUtils: GeoUtils,
    streetLayer: StreetLayer,
    outputDirMaybe: Option[String]
  ): FreightReader = {
    val tazMap = TAZTreeMap.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)
    apply(beamConfig, geoUtils, streetLayer, tazMap, outputDirMaybe)
  }

  def apply(beamServices: BeamServices): FreightReader =
    apply(
      beamServices.beamConfig,
      beamServices.geo,
      beamServices.beamScenario.transportNetwork.streetLayer,
      beamServices.beamScenario.tazTreeMap,
      None
    )
}
