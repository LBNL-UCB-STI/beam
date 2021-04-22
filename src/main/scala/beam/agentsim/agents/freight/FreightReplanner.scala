package beam.agentsim.agents.freight

import beam.agentsim.agents.freight.input.PayloadPlansConverter
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.jspritwrapper._
import beam.router.Modes.BeamMode
import beam.router.skim.readonly.ODSkims
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.{Person, Plan}
import org.matsim.api.core.v01.{Coord, Id}

import java.util
import scala.collection.JavaConverters._
import scala.language.implicitConversions

/**
  * @author Dmitry Openkov
  */
class FreightReplanner(
  beamServices: BeamServices,
  skimmer: ODSkims,
) extends LazyLogging {

  def replanIfNeeded(
    freightCarrier: FreightCarrier,
    iteration: Int,
    freightConfig: BeamConfig.Beam.Agentsim.Agents.Freight
  ): Unit = {
    if (FreightReplanner.needReplanning(iteration, freightCarrier, freightConfig)) {
      replan(freightCarrier)
    }
  }

  def replan(freightCarrier: FreightCarrier): Unit = {
    val strategyName = beamServices.beamConfig.beam.agentsim.agents.freight.replanning.strategy
    val solution = solve(freightCarrier, strategyName)
    val population = beamServices.matsimServices.getScenario.getPopulation.getPersons
    val intervalBetweenTours = beamServices.beamConfig.beam.agentsim.agents.freight.replanning.timeIntervalBetweenTours
    val newPlans = convertToPlans(solution, population, freightCarrier, intervalBetweenTours)

    newPlans.foreach { newPlan =>
      val person = newPlan.getPerson
      val oldPlans = person.getPlans.asScala.toIndexedSeq
      person.addPlan(newPlan)
      person.setSelectedPlan(newPlan)
      oldPlans.foreach(plan => person.removePlan(plan))
    }
  }

  private def convertToPlans(
    routes: IndexedSeq[Route],
    population: util.Map[Id[Person], _ <: Person],
    freightCarrier: FreightCarrier,
    intervalBetweenTours: Int
  ): Iterable[Plan] = {
    routes.groupBy(_.vehicle).map {
      case (vehicle, routes) =>
        val vehicleId = Id.createVehicleId(vehicle.id)
        val person = population.get(PayloadPlansConverter.createPersonId(vehicleId))

        val startTime = freightCarrier.tourMap(vehicleId).head.departureTimeInSec

        val (tours, plansPerTour) =
          convertToFreightTours(routes, startTime, intervalBetweenTours, freightCarrier.payloadPlans)

        val convertWgs2Utm = beamServices.beamConfig.beam.exchange.scenario.convertWgs2Utm
        PayloadPlansConverter
          .createPersonPlan(tours, plansPerTour, person, if (convertWgs2Utm) Some(beamServices.geo) else None)
    }
  }

  private def convertToFreightTours(
    routes: IndexedSeq[Route],
    startTime: Int,
    timeIntervalBetweenTours: Int,
    payloadPlans: Map[Id[PayloadPlan], PayloadPlan]
  ): (IndexedSeq[FreightTour], Map[Id[FreightTour], IndexedSeq[PayloadPlan]]) = {
    val routesAndTours = routes.zipWithIndex.foldLeft(IndexedSeq.empty[(Route, FreightTour)]) {
      case (acc, (route, i)) =>
        val tourStartTime: Double = acc.lastOption
          .map {
            case (route, tour) => tour.departureTimeInSec + route.duration + timeIntervalBetweenTours
          }
          .getOrElse(startTime)
        acc :+ route -> FreightTour(
          s"${route.vehicle.id}-$i".createId,
          tourStartTime,
          route.startLocation,
          route.duration * 2
        )
    }
    val tours: IndexedSeq[FreightTour] = routesAndTours.map(_._2)

    val plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]] = routesAndTours.map {
      case (route, tour) =>
        tour.tourId -> route.activities.zipWithIndex.map {
          case (activity, i) =>
            val freightRequestType: FreightRequestType = activity.service match {
              case _: Dropoff => FreightRequestType.Unloading
              case _: Pickup  => FreightRequestType.Loading
            }
            val payloadPlan = payloadPlans(activity.service.id.createId)
            PayloadPlan(
              activity.service.id.createId,
              i,
              tour.tourId,
              payloadPlan.payloadType,
              activity.service.capacity,
              freightRequestType,
              activity.service.location,
              activity.arrivalTime,
              payloadPlan.arrivalTimeWindowInSec,
              payloadPlan.operationDurationInSec,
            )
        }
    }.toMap
    (tours, plansPerTour)
  }

  private implicit def toInt(value: Double): Int = Math.round(value).toInt
  private implicit def toLocation(coord: Coord): Location = Location(coord.getX, coord.getY)
  private implicit def toCoord(location: Location): Coord = new Coord(location.x, location.y)

  private def getVehicleHouseholdLocation(vehicle: BeamVehicle): Location = {
    val householdIdStr = PayloadPlansConverter.createHouseholdId(vehicle.id).toString
    val x = beamServices.matsimServices.getScenario.getHouseholds.getHouseholdAttributes
      .getAttribute(householdIdStr, "homecoordx")
      .asInstanceOf[Double]
    val y = beamServices.matsimServices.getScenario.getHouseholds.getHouseholdAttributes
      .getAttribute(householdIdStr, "homecoordy")
      .asInstanceOf[Double]

    Location(x, y)
  }

  private def solve(freightCarrier: FreightCarrier, strategyName: String): IndexedSeq[Route] = {

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
        fuelPrice,
        beamServices.beamScenario
      )
      TimeDistanceCost(skim.time, skim.distance, skim.cost)
    }

    def toService(payloadPlan: PayloadPlan): Service = {
      val serviceId = payloadPlan.payloadId.toString
      payloadPlan.requestType match {
        case FreightRequestType.Unloading =>
          Dropoff(serviceId, payloadPlan.location, payloadPlan.weight, payloadPlan.operationDurationInSec)
        case FreightRequestType.Loading =>
          Pickup(serviceId, payloadPlan.location, payloadPlan.weight, payloadPlan.operationDurationInSec)
      }
    }

    def toJspritVehicle(beamVehicle: BeamVehicle) = {
      Vehicle(
        beamVehicle.id.toString,
        getVehicleHouseholdLocation(beamVehicle),
        beamVehicle.beamVehicleType.payloadCapacityInKg.get
      )
    }

    def solveForTheWholeFeet: Solution = {
      val vehicles = freightCarrier.fleet.values.map(toJspritVehicle).toIndexedSeq
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
        vehicles = IndexedSeq(toJspritVehicle(beamVehicle))
        tour <- tours
        services = freightCarrier.plansPerTour(tour.tourId).map(toService)
      } yield JspritWrapper.solve(Problem(vehicles, services, Some(calculateCost)))

      Monoid.combineAll(tourSolutions)
    }

    val strategy = strategyName match {
      case "wholeFleet" => solveForTheWholeFeet
      case "singleTour" => solveForVehicleTour
      case _ =>
        logger.error("Unknown freight replanning strategy {}, using 'vehicleTour'", strategyName)
        solveForVehicleTour
    }

    val solution = strategy
    solution.routes.foreach(
      route =>
        logger.debug(
          "Found route for vehicle {}, start time {}, number of services: {}",
          route.vehicle.id,
          route.startTime,
          route.activities.size
      )
    )

    if (solution.unassigned.nonEmpty) {
      logger.warn(s"Some plans are unassigned for freight carrier ${freightCarrier.carrierId}")
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
    val endIteration = freightConfig.replanning.endIteration
    enabled && iteration > 0 && iteration <= endIteration && freightCarrier.tourMap.nonEmpty
  }
}
