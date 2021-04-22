package beam.agentsim.agents.freight

import beam.agentsim.agents.freight.input.PayloadPlansConverter
import beam.agentsim.agents.vehicles.BeamVehicle
import beam.jspritwrapper._
import beam.router.Modes.BeamMode
import beam.router.skim.readonly.ODSkims
import beam.sim.BeamServices
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population.Plan
import org.matsim.api.core.v01.{Coord, Id}

import scala.collection.JavaConverters._
import scala.language.implicitConversions

/**
  * @author Dmitry Openkov
  */
class FreightReplanner(
  beamServices: BeamServices,
  freightCarrier: FreightCarrier,
  skimmer: ODSkims,
) extends LazyLogging {

  private def needReplanning(iteration: Int): Boolean = {
    val enabled = beamServices.beamConfig.beam.agentsim.agents.freight.enabled
    val endIteration = beamServices.beamConfig.beam.agentsim.agents.freight.replanning.endIteration
    val currentIteration = iteration
    enabled && currentIteration > 0 && currentIteration <= endIteration && freightCarrier.tourMap.nonEmpty
  }

  def replanning(iteration: Int): Unit = {
    if (needReplanning(iteration)) {
      val solution = solve()
      val population = beamServices.matsimServices.getScenario.getPopulation
      solution.routes.groupBy(_.vehicle).foreach {
        case (vehicle, routes) =>
          val vehicleId = Id.createVehicleId(vehicle.id)
          val person = population.getPersons.get(PayloadPlansConverter.createPersonId(vehicleId))
          val oldPlans = person.getPlans.asScala.toIndexedSeq

          val startTime = freightCarrier.tourMap(vehicleId).head.departureTimeInSec

          val (tours, plansPerTour) = convertToFreightTours(routes, startTime, 15 * 60)

          val convertWgs2Utm = beamServices.beamConfig.beam.exchange.scenario.convertWgs2Utm
          val currentPlan: Plan = PayloadPlansConverter
            .createPersonPlan(tours, plansPerTour, person, if (convertWgs2Utm) Some(beamServices.geo) else None)

          person.addPlan(currentPlan)
          person.setSelectedPlan(currentPlan)
          oldPlans.foreach(plan => person.removePlan(plan))
          println(person)
      }
    }
  }

  private def convertToFreightTours(routes: IndexedSeq[Route], startTime: Int, timeIntervalBetweenTours: Int) = {
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
            val payloadPlan = freightCarrier.payloadPlans(activity.service.id.createId)
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

  private def calculateCost(
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

  private def solve(): Solution = {
    def toService(payloadPlan: PayloadPlan): Service = {
      val serviceId = payloadPlan.payloadId.toString
      payloadPlan.requestType match {
        case FreightRequestType.Unloading =>
          Dropoff(serviceId, payloadPlan.location, payloadPlan.weight, payloadPlan.operationDurationInSec)
        case FreightRequestType.Loading =>
          Pickup(serviceId, payloadPlan.location, payloadPlan.weight, payloadPlan.operationDurationInSec)
      }
    }

    val vehicles = freightCarrier.fleet.values.map { beamVehicle =>
      Vehicle(
        beamVehicle.id.toString,
        getVehicleHouseholdLocation(beamVehicle),
        beamVehicle.beamVehicleType.payloadCapacityInKg.get
      )
    }.toIndexedSeq
    val services = freightCarrier.payloadPlans.values.map(plan => toService(plan)).toIndexedSeq
    val solution = JspritWrapper.solve(Problem(vehicles, services, Some(calculateCost)))
    if (solution.unassigned.nonEmpty) {
      logger.warn(s"Some plans are unassigned for freight carrier ${freightCarrier.carrierId}")
    }
    solution.routes.foreach(
      route =>
        logger.debug(
          "Found route for vehicle {}, start time {}, number of services: {}",
          route.vehicle.id,
          route.startTime,
          route.activities.size
      )
    )
    solution
  }
}
