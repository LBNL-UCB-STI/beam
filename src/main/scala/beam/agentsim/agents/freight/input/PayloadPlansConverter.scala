package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight._
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleManager}
import beam.agentsim.events.SpaceTime
import beam.router.Modes.BeamMode
import beam.sim.common.GeoUtils
import beam.utils.csv.GenericCsvReader
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{Household, HouseholdsFactory, Income, IncomeImpl}
import org.matsim.vehicles.Vehicle

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
          createVehicleId(tourId),
          departureTimeInSec,
          new Coord(departureLocationX, departureLocationY),
          maxTourDurationInSec
        )
      }
      .groupBy(_.tourId)
      .mapValues(_.head)
  }

  private def createVehicleId(tourId: Id[FreightTour]): Id[Vehicle] = Id.createVehicleId(s"freight-$tourId")

  def readPayloadPlans(path: String): Map[Id[PayloadPlan], PayloadPlan] = {
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
          new Coord(row.get("locationX").toDouble, row.get("locationY").toDouble),
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
    rnd: Random,
  ): IndexedSeq[FreightCarrier] = {

    case class FreightCarrierRow(
      carrierId: Id[FreightCarrier],
      tourId: Id[FreightTour],
      vehicleId: Id[BeamVehicle],
      vehicleTypeId: Id[BeamVehicleType],
      depotLocation: Coord
    )

    def createCarrierVehicles(
      carrierId: Id[FreightCarrier],
      carrierRows: IndexedSeq[FreightCarrierRow],
      tours: Map[Id[FreightTour], FreightTour],
    ) = {
      val vehicles: IndexedSeq[BeamVehicle] = carrierRows.map { row =>
        val vehicleType = vehicleTypes.getOrElse(
          row.vehicleTypeId,
          throw new IllegalArgumentException(
            s"Vehicle type for vehicle ${row.vehicleId} not found: ${row.vehicleTypeId}"
          )
        )
        val initialLocation = tours
          .getOrElse(row.tourId, throw new IllegalArgumentException(s"Tour with id ${row.tourId}"))
          .warehouseLocation
        createFreightVehicle(row.vehicleId, vehicleType, carrierId, initialLocation, rnd.nextInt())
      }.toIndexedSeq
      vehicles
    }

    def createCarrier(carrierId: Id[FreightCarrier], carrierRows: IndexedSeq[FreightCarrierRow]) = {
      val vehicles: scala.IndexedSeq[BeamVehicle] = createCarrierVehicles(carrierId, carrierRows, tours)
      val vehicleMap: Map[Id[BeamVehicle], BeamVehicle] = vehicles.map(vehicle => vehicle.id -> vehicle).toMap

      val tourMap: Map[Id[BeamVehicle], FreightTour] = carrierRows
        .map(
          row =>
            row.vehicleId -> tours.getOrElse(
              row.tourId,
              throw new IllegalArgumentException(s"Tour with id ${row.tourId} does not exist; check freight-tours.csv")
          )
        )
        .toMap

      val carrierTours = tourMap.values.map(_.tourId).toSet

      val plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]] =
        plans.values.groupBy(_.tourId).filterKeys(carrierTours).mapValues(_.toIndexedSeq.sortBy(_.sequenceRank))
      val carrierPlanIds: Set[Id[PayloadPlan]] = plansPerTour.values.reduce(_ ++ _).map(_.payloadId).toSet
      val payloadMap = plans.filterKeys(carrierPlanIds)

      FreightCarrier(carrierId, tourMap, payloadMap, vehicleMap, plansPerTour)
    }

    val rows = GenericCsvReader.readAsSeq[FreightCarrierRow](path) { row =>
      //carrierId,tourId,vehicleTypeId,depotLocationX,depotLocationY
      val carrierId: Id[FreightCarrier] = row.get("carrierId").createId
      val tourId: Id[FreightTour] = row.get("tourId").createId
      val vehicleId: Id[BeamVehicle] = createVehicleId(tourId)
      val vehicleTypeId: Id[BeamVehicleType] = row.get("vehicleTypeId").createId
      val depotLocationX = row.get("depotLocationX").toDouble
      val depotLocationY = row.get("depotLocationY").toDouble
      FreightCarrierRow(carrierId, tourId, vehicleId, vehicleTypeId, new Coord(depotLocationX, depotLocationY))
    }
    rows
      .groupBy(_.carrierId)
      .map {
        case (carrierId, carrierRows) =>
          createCarrier(carrierId, carrierRows)
      }
      .toIndexedSeq
  }

  def createFreightVehicle(
    vehicleId: Id[Vehicle],
    vehicleType: BeamVehicleType,
    carrierId: Id[FreightCarrier],
    initialLocation: Coord,
    randomSeed: Int,
  ): BeamVehicle = {
    val powertrain = Option(vehicleType.primaryFuelConsumptionInJoulePerMeter)
      .map(new Powertrain(_))
      .getOrElse(Powertrain.PowertrainFromMilesPerGallon(Powertrain.AverageMilesPerGallon))

    val beamVehicleId = BeamVehicle.createId(vehicleId)

    val vehicle = new BeamVehicle(
      beamVehicleId,
      powertrain,
      vehicleType,
      managerId = carrierId.toString.createId[VehicleManager],
      randomSeed
    )
    vehicle.spaceTime = SpaceTime(initialLocation, 0)
    vehicle
  }

  def generatePopulation(
    carriers: IndexedSeq[FreightCarrier],
    personFactory: PopulationFactory,
    householdsFactory: HouseholdsFactory,
    convertWgs2Utm: Boolean,
    geo: GeoUtils
  ): IndexedSeq[(Household, Plan)] = {
    def createActivity(activityType: String, location: Coord, endTime: Int) = {
      val coord = if (convertWgs2Utm) geo.wgs2Utm(location) else location
      val act = PopulationUtils.createActivityFromCoord(activityType, coord)
      if (endTime >= 0) {
        act.setEndTime(endTime)
      }
      act
    }

    def createLeg(departureTime: Int) = {
      val leg = PopulationUtils.createLeg(BeamMode.CAR.value)
      leg.setDepartureTime(departureTime)
      leg
    }

    carriers.flatMap { carrier =>
      carrier.tourMap.values.map { tour =>
        val personId = Id.createPersonId(s"freight-agent-${tour.tourId}")
        val person = personFactory.createPerson(personId)

        val initialActivity = createActivity("Warehouse", tour.warehouseLocation, tour.departureTimeInSec)
        val firstLeg: Leg = createLeg(tour.departureTimeInSec)

        val plans: IndexedSeq[PayloadPlan] = carrier.plansPerTour(tour.tourId)
        val planElements: IndexedSeq[PlanElement] = plans.flatMap { plan =>
          val activityEndTime = plan.estimatedTimeOfArrivalInSec + plan.operationDurationInSec
          val activityType = plan.requestType.toString
          val activity = createActivity(activityType, plan.location, activityEndTime)
          val leg: Leg = createLeg(activityEndTime)
          Seq(activity, leg)
        }

        val finalActivity = createActivity("Warehouse", tour.warehouseLocation, -1)

        val allPlanElements: IndexedSeq[PlanElement] = initialActivity +: firstLeg +: planElements :+ finalActivity

        val currentPlan = PopulationUtils.createPlan(person)
        allPlanElements.foreach {
          case activity: Activity => currentPlan.addActivity(activity)
          case leg: Leg           => currentPlan.addLeg(leg)
          case _                  => throw new UnknownError() //shouldn't happen
        }
        person.addPlan(currentPlan)
        person.setSelectedPlan(currentPlan)

        val freightHouseholdId = s"freight-household-${tour.tourId}".createId[Household]
        val household: Household = householdsFactory.createHousehold(freightHouseholdId)
        household.setIncome(new IncomeImpl(44444, Income.IncomePeriod.year))
        household.getMemberIds.add(personId)
        household.getVehicleIds.add(tour.vehicleId)
        household.getAttributes

        (household, currentPlan)
      }
    }
  }

}
