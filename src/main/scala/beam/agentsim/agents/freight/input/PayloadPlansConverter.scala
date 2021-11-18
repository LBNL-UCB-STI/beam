package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.router.Modes.BeamMode
import beam.sim.common.GeoUtils
import beam.utils.csv.GenericCsvReader
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{Household, HouseholdsFactory, Income, IncomeImpl}
import org.matsim.vehicles.Vehicle

import java.util.concurrent.atomic.AtomicReference
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
object PayloadPlansConverter {

  def readFreightTours(path: String): Map[Id[FreightTour], FreightTour] = {
    GenericCsvReader
      .readAsSeq[FreightTour](path) { row =>
        //tourId,departureTimeInSec,departureLocationX,departureLocationY,maxTourDurationInSec
        val tourId: Id[FreightTour] = row.get("tourId").createId[FreightTour]
        val departureTimeInSec = row.get("departureTimeInSec").toInt
        val departureLocationX = row.get("departureLocationX").toDouble
        val departureLocationY = row.get("departureLocationY").toDouble
        val maxTourDurationInSec = row.get("maxTourDurationInSec").toInt
        FreightTour(
          tourId,
          departureTimeInSec,
          new Coord(departureLocationX, departureLocationY),
          maxTourDurationInSec
        )
      }
      .groupBy(_.tourId)
      .mapValues(_.head)
  }

  private def getDistributedTazLocation(tazId: String, tazTree: TAZTreeMap, rnd: Random): Coord =
    tazTree.getTAZ(tazId) match {
      case Some(taz) => TAZTreeMap.randomLocationInTAZ(taz, rnd)
      case None      => throw new IllegalArgumentException(s"Cannot find taz with id $tazId")
    }

  def readPayloadPlans(path: String, tazTree: TAZTreeMap, rnd: Random): Map[Id[PayloadPlan], PayloadPlan] = {
    GenericCsvReader
      .readAsSeq[PayloadPlan](path) { row =>
        //payloadId,sequenceRank,tourId,payloadType,weightInKg,requestType,locationX,locationY,estimatedTimeOfArrivalInSec,arrivalTimeWindowInSec,operationDurationInSec
        PayloadPlan(
          row.get("payloadId").createId,
          row.get("sequenceRank").toInt,
          row.get("tourId").createId,
          row.get("payloadType").createId[PayloadType],
          row.get("weightInKg").toDouble,
          FreightRequestType.withNameInsensitive(row.get("requestType")),
          getDistributedTazLocation(row.get("taz"), tazTree, rnd),
          row.get("estimatedTimeOfArrivalInSec").toInt,
          row.get("arrivalTimeWindowInSec").toInt,
          row.get("operationDurationInSec").toInt
        )
      }
      .groupBy(_.payloadId)
      .mapValues(_.head)
  }

  def readFreightCarriers(
    path: String,
    tours: Map[Id[FreightTour], FreightTour],
    plans: Map[Id[PayloadPlan], PayloadPlan],
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
    tazTree: TAZTreeMap,
    rnd: Random
  ): IndexedSeq[FreightCarrier] = {

    case class FreightCarrierRow(
      carrierId: Id[FreightCarrier],
      tourId: Id[FreightTour],
      vehicleId: Id[BeamVehicle],
      vehicleTypeId: Id[BeamVehicleType],
      warehouseTaz: String
    )

    def createCarrierVehicles(
      carrierId: Id[FreightCarrier],
      carrierRows: IndexedSeq[FreightCarrierRow],
      warehouseLocation: Coord
    ): IndexedSeq[BeamVehicle] = {
      val vehicles: IndexedSeq[BeamVehicle] = carrierRows
        .groupBy(_.vehicleId)
        .map { case (vehicleId, rows) =>
          val firstRow = rows.head
          val vehicleType = vehicleTypes.getOrElse(
            firstRow.vehicleTypeId,
            throw new IllegalArgumentException(
              s"Vehicle type for vehicle $vehicleId not found: ${firstRow.vehicleTypeId}"
            )
          )
          if (vehicleType.payloadCapacityInKg.isEmpty)
            throw new IllegalArgumentException(
              s"Vehicle type ${firstRow.vehicleTypeId} for vehicle $vehicleId has no payloadCapacityInKg defined"
            )
          createFreightVehicle(vehicleId, vehicleType, carrierId, warehouseLocation, rnd.nextInt())
        }
        .toIndexedSeq
      vehicles
    }

    def createCarrier(carrierId: Id[FreightCarrier], carrierRows: IndexedSeq[FreightCarrierRow]) = {
      val warehouseLocation: Coord = getDistributedTazLocation(carrierRows.head.warehouseTaz, tazTree, rnd)
      val vehicles: scala.IndexedSeq[BeamVehicle] = createCarrierVehicles(carrierId, carrierRows, warehouseLocation)
      val vehicleMap: Map[Id[BeamVehicle], BeamVehicle] = vehicles.map(vehicle => vehicle.id -> vehicle).toMap

      val tourMap: Map[Id[BeamVehicle], IndexedSeq[FreightTour]] = carrierRows
        .groupBy(_.vehicleId)
        .mapValues { rows =>
          rows
            //setting the tour warehouse location to be the carrier warehouse location
            .map(row => tours(row.tourId).copy(warehouseLocation = warehouseLocation))
            .sortBy(_.departureTimeInSec)
        }

      val carrierTourIds = tourMap.values.flatten.map(_.tourId).toSet

      val plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]] =
        plans.values.groupBy(_.tourId).filterKeys(carrierTourIds).mapValues(_.toIndexedSeq.sortBy(_.sequenceRank))
      val carrierPlanIds: Set[Id[PayloadPlan]] = plansPerTour.values.flatten.map(_.payloadId).toSet
      val payloadMap = plans.filterKeys(carrierPlanIds)

      FreightCarrier(carrierId, tourMap, payloadMap, vehicleMap, plansPerTour)
    }

    val rows = GenericCsvReader.readAsSeq[FreightCarrierRow](path) { row =>
      //carrierId,tourId,vehicleId,vehicleTypeId,warehouseTAZ
      val carrierId: Id[FreightCarrier] = row.get("carrierId").createId
      val tourId: Id[FreightTour] = row.get("tourId").createId
      val vehicleId: Id[BeamVehicle] = Id.createVehicleId(row.get("vehicleId"))
      val vehicleTypeId: Id[BeamVehicleType] = row.get("vehicleTypeId").createId
      val warehouseTaz = row.get("warehouseTAZ")
      FreightCarrierRow(carrierId, tourId, vehicleId, vehicleTypeId, warehouseTaz)
    }
    rows
      .groupBy(_.carrierId)
      .map { case (carrierId, carrierRows) =>
        createCarrier(carrierId, carrierRows)
      }
      .toIndexedSeq
  }

  private def createFreightVehicle(
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

  def generatePopulation(
    carriers: IndexedSeq[FreightCarrier],
    personFactory: PopulationFactory,
    householdsFactory: HouseholdsFactory,
    geoConverter: Option[GeoUtils]
  ): IndexedSeq[(Household, Plan)] = {

    carriers.flatMap { carrier =>
      carrier.tourMap.map { case (vehicleId, tours) =>
        val personId = createPersonId(vehicleId)
        val person = personFactory.createPerson(personId)

        val currentPlan: Plan = createPersonPlan(tours, carrier.plansPerTour, person, geoConverter)

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

  private def createActivity(activityType: String, location: Coord, endTime: Int, geo: Option[GeoUtils]) = {
    val coord = geo.map(_.wgs2Utm(location)).getOrElse(location)
    val act = PopulationUtils.createActivityFromCoord(activityType, coord)
    if (endTime >= 0) {
      act.setEndTime(endTime)
    }
    act
  }

  private def createLeg(departureTime: Int) = {
    val leg = PopulationUtils.createLeg(BeamMode.CAR.value)
    leg.setDepartureTime(departureTime)
    leg
  }

  def createPersonPlan(
    tours: IndexedSeq[FreightTour],
    plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]],
    person: Person,
    geoConverter: Option[GeoUtils]
  ): Plan = {
    val allToursPlanElements = tours.flatMap { tour =>
      val tourInitialActivity =
        createActivity("Warehouse", tour.warehouseLocation, tour.departureTimeInSec, geoConverter)
      val firstLeg: Leg = createLeg(tour.departureTimeInSec)

      val plans: IndexedSeq[PayloadPlan] =
        plansPerTour.getOrElse(tour.tourId, throw new IllegalArgumentException(s"Tour ${tour.tourId} has no plans"))
      val planElements: IndexedSeq[PlanElement] = plans.flatMap { plan =>
        val activityEndTime = plan.estimatedTimeOfArrivalInSec + plan.operationDurationInSec
        val activityType = plan.requestType.toString
        val activity = createActivity(activityType, plan.location, activityEndTime, geoConverter)
        val leg: Leg = createLeg(activityEndTime)
        Seq(activity, leg)
      }

      tourInitialActivity +: firstLeg +: planElements
    }

    val finalActivity = createActivity("Warehouse", tours.head.warehouseLocation, -1, geoConverter)
    val allPlanElements: IndexedSeq[PlanElement] = allToursPlanElements :+ finalActivity

    val currentPlan = PopulationUtils.createPlan(person)
    allPlanElements.foreach {
      case activity: Activity => currentPlan.addActivity(activity)
      case leg: Leg           => currentPlan.addLeg(leg)
      case _                  => throw new UnknownError() //shouldn't happen
    }
    currentPlan
  }

  def createPersonId(vehicleId: Id[BeamVehicle]): Id[Person] = Id.createPersonId(s"freight-agent-$vehicleId")

  def createHouseholdId(vehicleId: Id[BeamVehicle]): Id[Household] = s"freight-household-$vehicleId".createId
}
