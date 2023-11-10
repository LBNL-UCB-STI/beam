package beam.agentsim.agents.freight

import beam.agentsim.agents.freight.input.FreightReader
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.jspritwrapper._
import beam.router.Modes.BeamMode
import beam.router.skim.readonly.ODSkims
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Person, Plan}
import org.matsim.api.core.v01.{Coord, Id}

import java.util
import scala.collection.JavaConverters._
import scala.language.implicitConversions
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class FreightReplanner(
  beamServices: BeamServices,
  skimmer: ODSkims,
  rnd: Random,
  freightReader: FreightReader
) extends LazyLogging {
  val freightConfig: Freight = freightReader.config

  def replanIfNeeded(freightCarrier: FreightCarrier, iteration: Int): Unit = {
    if (FreightReplanner.needReplanning(iteration, freightCarrier, freightConfig)) {
      replan(freightCarrier)
    }
  }

  def replan(freightCarrier: FreightCarrier): Unit = {
    val strategyName = beamServices.beamConfig.beam.agentsim.agents.freight.replanning.strategy
    val departureTime = beamServices.beamConfig.beam.agentsim.agents.freight.replanning.departureTime
    val routes = calculateRoutes(freightCarrier, strategyName, departureTime)
    val population = beamServices.matsimServices.getScenario.getPopulation.getPersons

    val newPlans = convertToPlans(routes, population, freightCarrier)

    newPlans.foreach { newPlan =>
      val person = newPlan.getPerson
      setPlanToPerson(newPlan, person)
    }
  }

  private def setPlanToPerson(newPlan: Plan, person: Person): Unit = {
    val oldPlans = person.getPlans.asScala.toIndexedSeq
    person.addPlan(newPlan)
    person.setSelectedPlan(newPlan)
    oldPlans.foreach(person.removePlan)
  }

  private[freight] def convertToPlans(
    routes: IndexedSeq[Route],
    population: util.Map[Id[Person], _ <: Person],
    freightCarrier: FreightCarrier
  ): Iterable[Plan] = {
    routes.groupBy(_.vehicle.id).map { case (vehicleIdStr, routes) =>
      val vehicleId = Id.createVehicleId(vehicleIdStr)
      val person = population.get(freightReader.createPersonId(freightCarrier.carrierId, vehicleId))
      val toursAndPlans = routes.zipWithIndex.map { case (route, i) =>
        convertToFreightTourWithPayloadPlans(
          s"${route.vehicle.id}-$i".createId,
          route,
          freightCarrier.payloadPlans
        )
      }
      val tours = toursAndPlans.map(_._1)
      val plansPerTour = toursAndPlans.map { case (tour, plans) => tour.tourId -> plans }.toMap
      freightReader.createPersonPlan(freightCarrier, tours, plansPerTour, person)
    }
  }

  private def convertToFreightTourWithPayloadPlans(
    tourId: Id[FreightTour],
    route: Route,
    payloadPlans: Map[Id[PayloadPlan], PayloadPlan]
  ): (FreightTour, IndexedSeq[PayloadPlan]) = {
    val tour = FreightTour(tourId, route.startTime, route.duration * 2)

    val plans = route.activities.zipWithIndex.map { case (activity, i) =>
      val requestType: FreightRequestType = activity.service match {
        case _: Dropoff => FreightRequestType.Unloading
        case _: Pickup  => FreightRequestType.Loading
      }
      val payloadPlan = payloadPlans(activity.service.id.createId)

      val activityType = if (freightConfig.generateFixedActivitiesDurations) {
        s"${requestType.toString}|${payloadPlan.operationDurationInSec}"
      } else {
        requestType.toString
      }

      PayloadPlan(
        activity.service.id.createId,
        i,
        tour.tourId,
        payloadPlan.payloadType,
        activity.service.capacity,
        requestType,
        activityType,
        None,
        activity.service.location,
        activity.arrivalTime,
        payloadPlan.arrivalTimeWindowInSecLower,
        payloadPlan.arrivalTimeWindowInSecUpper,
        payloadPlan.operationDurationInSec
      )
    }
    (tour, plans)
  }

  private implicit def toInt(value: Double): Int = Math.round(value).toInt
  private implicit def toLocation(coord: Coord): Location = Location(coord.getX, coord.getY)
  private implicit def toCoord(location: Location): Coord = new Coord(location.x, location.y)

  private def getVehicleHouseholdLocation(carrierId: Id[FreightCarrier]): Location = {
    val householdIdStr = freightReader.createHouseholdId(carrierId).toString
    val x = Option(
      beamServices.matsimServices.getScenario.getHouseholds.getHouseholdAttributes
        .getAttribute(householdIdStr, "homecoordx")
    ).map(_.toString.toDouble).getOrElse {
      logger.error(
        s"Cannot find homeCoordX for freight carrier $carrierId which will be interpreted at 0.0"
      )
      0.0
    }
    val y = Option(
      beamServices.matsimServices.getScenario.getHouseholds.getHouseholdAttributes
        .getAttribute(householdIdStr, "homecoordy")
    ).map(_.toString.toDouble).getOrElse {
      logger.error(
        s"Cannot find homeCoordY for household $carrierId which will be interpreted at 0.0"
      )
      0.0
    }
    Location(x, y)
  }

  private[freight] def calculateRoutes(
    freightCarrier: FreightCarrier,
    strategyName: String,
    departureTime: Int
  ): IndexedSeq[Route] = {

    def calculateCost(
      from: Location,
      to: Location,
      departureTime: Double,
      maybeVehicle: Option[Vehicle]
    ): TimeDistanceCost = {
      val beamVehicleType = (for {
        vehicle     <- maybeVehicle
        vehicleType <- freightCarrier.fleet.get(Id.createVehicleId(vehicle.id))
      } yield vehicleType.beamVehicleType).getOrElse(freightCarrier.fleet.values.head.beamVehicleType)

      val fuelPrice: Double = beamServices.beamScenario.fuelTypePrices(beamVehicleType.primaryFuelType)
      val skim = skimmer.getTimeDistanceAndCost(
        from,
        to,
        departureTime,
        BeamMode.CAR,
        beamVehicleType.id,
        beamVehicleType,
        fuelPrice
      )
      TimeDistanceCost(skim.time, skim.distance, skim.cost)
    }

    def toService(payloadPlan: PayloadPlan): Service = {
      val serviceId = payloadPlan.payloadId.toString
      payloadPlan.requestType match {
        case FreightRequestType.Unloading =>
          Dropoff(serviceId, payloadPlan.locationUTM, payloadPlan.weightInKg, payloadPlan.operationDurationInSec)
        case FreightRequestType.Loading =>
          Pickup(serviceId, payloadPlan.locationUTM, payloadPlan.weightInKg, payloadPlan.operationDurationInSec)
      }
    }

    def toJspritVehicle(carrierId: Id[FreightCarrier], beamVehicle: BeamVehicle, departureTime: Int) = {
      Vehicle(
        beamVehicle.id.toString,
        getVehicleHouseholdLocation(carrierId),
        beamVehicle.beamVehicleType.payloadCapacityInKg.get,
        departureTime
      )
    }

    def randomTimeAround(time: Int): Int = {
      val halfInterval = 3600
      val low = Math.max(time - halfInterval, 0)
      low + rnd.nextInt(2 * halfInterval)
    }

    def solveForTheWholeFeet: Solution = {
      val vehicles =
        freightCarrier.fleet.values
          .map(beamVehicle => {
            val departure = randomTimeAround(departureTime)
            toJspritVehicle(freightCarrier.carrierId, beamVehicle, departure)
          })
          .toIndexedSeq
      val services = freightCarrier.payloadPlans.values.map(toService).toIndexedSeq
      JspritWrapper.solve(Problem(vehicles, services, Some(calculateCost)))
    }

    def solveForVehicleTour: Solution = {
      import cats.Monoid
      implicit val solutionMonoid: Monoid[Solution] = new Monoid[Solution] {
        override def combine(x: Solution, y: Solution): Solution =
          Solution(x.routes ++ y.routes, x.unassigned ++ y.unassigned)

        override def empty: Solution = Solution(IndexedSeq.empty, IndexedSeq.empty)
      }

      val tourSolutions = for {
        (vehicleId, tours) <- freightCarrier.tourMap
        beamVehicle = freightCarrier.fleet(vehicleId)
        tour <- tours
        services = freightCarrier.plansPerTour(tour.tourId).map(toService)
        vehicles = IndexedSeq(toJspritVehicle(freightCarrier.carrierId, beamVehicle, tour.departureTimeInSec))
      } yield JspritWrapper.solve(Problem(vehicles, services, Some(calculateCost)))

      Monoid.combineAll(tourSolutions)
    }

    val solution = strategyName match {
      case "wholeFleet" => solveForTheWholeFeet
      case "singleTour" => solveForVehicleTour
      case _ =>
        logger.error("Unknown freight replanning strategy {}, using 'vehicleTour'", strategyName)
        solveForVehicleTour
    }

    solution.routes.foreach(route =>
      logger.debug(
        "Found route for vehicle {}, start time {}, number of services: {}",
        route.vehicle.id,
        route.startTime,
        route.activities.size
      )
    )

    if (solution.unassigned.nonEmpty) {
      logger.warn(s"Some plans are unassigned for freight carrier ${freightCarrier.carrierId}")
      solution.unassigned.foreach(x => logger.debug(s"unassigned payload $x"))
    }
    solution.routes
  }
}

object FreightReplanner {

  private def needReplanning(
    iteration: Int,
    freightCarrier: FreightCarrier,
    freightConfig: BeamConfig.Beam.Agentsim.Agents.Freight
  ): Boolean = {
    val enabled = freightConfig.enabled
    val lastReplanningIteration = freightConfig.replanning.disableAfterIteration
    enabled && iteration > 0 && iteration <= lastReplanningIteration && freightCarrier.tourMap.nonEmpty
  }
}
