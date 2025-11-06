package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight.FreightActivityType.{Depot, Loading, Unloading}
import beam.agentsim.agents.freight.input.FreightReader.{FREIGHT_REQUEST_TYPE, PAYLOAD_IDS, PAYLOAD_WEIGHT_IN_KG}
import beam.agentsim.agents.freight.{FreightActivityType, FreightCarrier, FreightTour, PayloadPlan}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.Modes.BeamMode
import beam.sim.BeamServices
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
import beam.utils.NetworkFilter
import beam.utils.SnapCoordinateUtils.SnapLocationHelper
import com.conveyal.r5.streets.StreetLayer
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{Household, HouseholdsFactory, Income, IncomeImpl}
import org.matsim.vehicles.Vehicle

import java.util.concurrent.atomic.AtomicReference
import scala.collection.JavaConverters._
import scala.util.Random

trait FreightReader {
  val geoUtils: GeoUtils
  val config: Freight

  def readFreightTours(): Map[Id[FreightTour], FreightTour]

  def readPayloadPlans(): Map[Id[PayloadPlan], PayloadPlan]

  def createPersonId(vehicleId: Id[BeamVehicle]): Id[Person]

  def createHouseholdId(carrierId: Id[FreightCarrier]): Id[Household]

  def readFreightCarriers(
    allTours: Map[Id[FreightTour], FreightTour],
    allPlans: Map[Id[PayloadPlan], PayloadPlan],
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]
  ): IndexedSeq[FreightCarrier]

  private def calculatePayloadWeights(plans: IndexedSeq[PayloadPlan]): IndexedSeq[(Set[Id[PayloadPlan]], Double)] = {
    plans.foldLeft(IndexedSeq((Set.empty[Id[PayloadPlan]], 0.0))) {
      case (acc, PayloadPlan(payloadId, _, _, _, weight, Unloading, _, _, _, _, _, _)) =>
        val (payloads, payloadWeight) = acc.last
        acc :+ (payloads - payloadId, payloadWeight - weight)
      case (acc, PayloadPlan(payloadId, _, _, _, weight, Loading, _, _, _, _, _, _)) =>
        val (payloads, payloadWeight) = acc.last
        acc :+ (payloads + payloadId, payloadWeight + weight)
      case (acc, PayloadPlan(payloadId, _, _, _, weight, Depot, _, _, _, _, _, _)) =>
        val (payloads, payloadWeight) = acc.last
        acc :+ (payloads + payloadId, payloadWeight + weight)
    }
  }

  def createPersonPlan(
    carrier: FreightCarrier,
    tours: IndexedSeq[FreightTour],
    plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]],
    person: Person
  ): Plan = {
    val allPlanElements = tours.flatMap { tour =>
      val plans: IndexedSeq[PayloadPlan] =
        plansPerTour
          .getOrElse(tour.tourId, throw new IllegalArgumentException(s"Tour '${tour.tourId}' has no plans"))
          .ensuring(_.nonEmpty, s"Tour '${tour.tourId}' has an empty plan list")

      val planElements: IndexedSeq[PlanElement] = plans.flatMap { plan =>
        plan.sequenceRank match {
          case rank if rank == plans.head.sequenceRank =>
            val activity =
              createFreightActivity(
                FreightActivityType.Depot.toString,
                carrier.depotLocationUTM,
                tour.departureTimeInSec,
                None
              )
            val leg = createFreightLeg(tour.departureTimeInSec)
            Seq(activity, leg)

          case rank if rank == plans.last.sequenceRank =>
            val activity = createFreightActivity(
              FreightActivityType.Depot.toString,
              carrier.depotLocationUTM,
              -1,
              None
            )
            Seq(activity) // no leg for last

          case _ =>
            val actEndTime = plan.estimatedTimeOfArrivalInSec + plan.operationDurationInSec
            val actType = plan.activityType.toString
            val activity = createFreightActivity(actType, plan.locationUTM, actEndTime, Some(plan.activityType))
            val leg = createFreightLeg(actEndTime)
            Seq(activity, leg)
        }
      }

      val weightsToCarry: IndexedSeq[(Set[Id[PayloadPlan]], Double)] = calculatePayloadWeights(plans)
      planElements
        .collect { case leg: Leg => leg }
        .zip(weightsToCarry)
        .foreach { case (leg, (payloadIds, payloadWeight)) =>
          leg.getAttributes.putAttribute(PAYLOAD_IDS, payloadIds.toIndexedSeq)
          leg.getAttributes.putAttribute(PAYLOAD_WEIGHT_IN_KG, payloadWeight)
        }
      planElements
    }

    val currentPlan = PopulationUtils.createPlan(person)
    allPlanElements.foreach {
      case activity: Activity => currentPlan.addActivity(activity)
      case leg: Leg           => currentPlan.addLeg(leg)
      case _                  => throw new UnknownError() //shouldn't happen
    }
    currentPlan
  }

  def generatePopulation(
    carriers: Map[Id[FreightCarrier], FreightCarrier],
    populationFactory: PopulationFactory,
    householdsFactory: HouseholdsFactory
  ): IndexedSeq[(FreightCarrier, Household, Plan, Person, Id[BeamVehicle])] = {

    // Pre-size the buffer to avoid ArrayBuffer resizing
    val totalPersons = carriers.valuesIterator.map(_.tourMap.size).sum
    val results =
      new scala.collection.mutable.ArrayBuffer[(FreightCarrier, Household, Plan, Person, Id[BeamVehicle])](totalPersons)

    // Iterate directly over carrier values (avoids tuple destructuring from Map)
    val carrierIter = carriers.valuesIterator
    while (carrierIter.hasNext) {
      val carrier = carrierIter.next()

      // Create one household per carrier
      val freightCarrierId = createHouseholdId(carrier.carrierId)
      val household = householdsFactory.createHousehold(freightCarrierId)
      household.setIncome(new IncomeImpl(0, Income.IncomePeriod.year))

      // Iterate directly over tourMap entries for this carrier
      val tourIter = carrier.tourMap.iterator
      while (tourIter.hasNext) {
        val (vehicleId, tours) = tourIter.next()

        // Create person and plan
        val personId = createPersonId(vehicleId)
        val person = populationFactory.createPerson(personId)

        val currentPlan: Plan = createPersonPlan(carrier, tours, carrier.plansPerTour, person)
        person.addPlan(currentPlan)
        person.setSelectedPlan(currentPlan)

        // Link person and vehicle to household
        household.getMemberIds.add(personId)
        household.getVehicleIds.add(vehicleId)

        // Append tuple to results
        results += ((carrier, household, currentPlan, person, vehicleId))
      }
    }

    results.result()
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
        VehicleManager.createOrGetReservedFor(carrierId.toString, Some(VehicleManager.TypeEnum.Freight)).managerId
      ),
      randomSeed
    )
    vehicle.spaceTime = SpaceTime(initialLocation, 0)
    vehicle
  }

  private def createFreightActivity(
    activityType: String,
    locationUTM: Coord,
    endTime: Int,
    freightRequestType: Option[FreightActivityType]
  ): Activity = {
    val act = PopulationUtils.createActivityFromCoord(activityType, locationUTM)
    if (endTime >= 0) {
      act.setEndTime(endTime)
    }
    freightRequestType.foreach(act.getAttributes.putAttribute(FREIGHT_REQUEST_TYPE, _))
    act
  }

  private def createFreightLeg(departureTime: Int): Leg = {
    val leg = PopulationUtils.createLeg(BeamMode.CAR.value)
    leg.setDepartureTime(departureTime)
    leg
  }
}

object FreightReader {
  val FREIGHT_REQUEST_TYPE = "FreightActivityType"
  val PAYLOAD_WEIGHT_IN_KG = "PayloadWeightInKg"
  val PAYLOAD_IDS = "PayloadIds"
  val NO_CARRIER_ID: Id[FreightCarrier] = Id.create("no-carrier-defined", classOf[FreightCarrier])
  val NO_VEHICLE_ID: Id[BeamVehicle] = Id.createVehicleId("no-vehicle-defined")

  def apply(
    beamConfig: BeamConfig,
    geoUtils: GeoUtils,
    streetLayer: StreetLayer,
    network: Option[Network],
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
          beamConfig.beam.agentsim.snapLocationAndRemoveInvalidInputs,
          beamConfig.beam.agentsim.schedulerParallelismWindow,
          snapLocationHelper,
          network.map(NetworkFilter.filterNetworkByCarMode),
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
    network: Option[Network],
    outputDirMaybe: Option[String],
    tazTreeMapMaybe: Option[TAZTreeMap]
  ): FreightReader = {
    val tazMap = tazTreeMapMaybe.getOrElse {
      TAZTreeMap(
        beamConfig.beam.agentsim.taz.filePath,
        beamConfig.beam.spatial.localCRS,
        Some(beamConfig.beam.agentsim.taz.tazIdFieldName),
        network
          .map(_.getLinks.asScala.toMap)
          .getOrElse(Map.empty),
        beamConfig.beam.agentsim.agents.parking.search.params.enableLinkBasedSearch
      )
    }
    apply(beamConfig, geoUtils, streetLayer, network, tazMap, outputDirMaybe)
  }

  def apply(beamServices: BeamServices): FreightReader =
    apply(
      beamServices.beamConfig,
      beamServices.geo,
      beamServices.beamScenario.transportNetwork.streetLayer,
      Some(beamServices.beamScenario.network),
      beamServices.beamScenario.tazTreeMap,
      None
    )
}
