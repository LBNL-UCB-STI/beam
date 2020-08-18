package beam.physsim.jdeqsim

import scala.collection.mutable.ArrayBuffer
import scala.collection.JavaConverters._
import scala.util.Try

import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory}
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Access, RoutingRequest, RoutingResponse}
import beam.router.Modes.BeamMode.CAR
import beam.router.r5.{R5Parameters, R5Wrapper}
import beam.sim.BeamServices
import beam.sim.population.AttributesOfIndividual
import beam.utils.{ProfilingUtils, Statistics}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.{Leg, Person, Population}
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.router.util.TravelTime

class ReRouter(val workerParams: R5Parameters, val beamServices: BeamServices) extends StrictLogging {

  private val (_: Id[BeamVehicleType], carVehType: BeamVehicleType) = beamServices.beamScenario.vehicleTypes
    .collect { case (k, v) if v.vehicleCategory == VehicleCategory.Car => (k, v) }
    .maxBy(_._2.sampleProbabilityWithinCategory)

  def reroutePeople(travelTime: TravelTime, toReroute: Vector[Person]): Statistics = {
    if (toReroute.nonEmpty) {
      val personToRoutes = toReroute.flatMap(_.getPlans.asScala.toVector).map { plan =>
        val route = plan.getPlanElements.asScala.zipWithIndex.collect {
          case (leg: Leg, idx: Int) if leg.getMode.equalsIgnoreCase("car") =>
            ElementIndexToLeg(idx, leg)
        }.toVector
        plan.getPerson -> route
      }
      val result = getNewRoutes(toReroute, personToRoutes, travelTime)
      val oldTravelTimes = new ArrayBuffer[Double]()
      val newTravelTimes = new ArrayBuffer[Double]()
      ProfilingUtils.timed(s"Update routes for ${toReroute.size} people", x => logger.info(x)) {
        updatePlans(oldTravelTimes, newTravelTimes, result)
        // We're assuming this should go down
        logger.info(
          s"Old total travel time for rerouted people: ${Statistics(oldTravelTimes.map(x => x / 60).toArray)}"
        )
        logger.info(
          s"New total travel time for rerouted people: ${Statistics(newTravelTimes.map(x => x / 60).toArray)}"
        )
      }
      Statistics(newTravelTimes.map(x => x / 60).toArray)
    } else
      Statistics(Array.empty[Double])
  }

  private def updatePlans(
    newTravelTimes: ArrayBuffer[Double],
    oldTravelTimes: ArrayBuffer[Double],
    result: Seq[(Person, Vector[ElementIndexToRoutingResponse])]
  ): Unit = {
    result.foreach {
      case (person, xs) =>
        val elems = person.getSelectedPlan.getPlanElements.asScala
        xs.foreach {
          case ElementIndexToRoutingResponse(index, maybeResp) =>
            elems(index) match {
              case leg: Leg =>
                maybeResp.fold(
                  ex => logger.error(s"Can't compute the route: ${ex.getMessage}", ex),
                  (resp: RoutingResponse) => {
                    resp.itineraries.headOption.flatMap(_.legs.headOption.map(_.beamLeg)) match {
                      case Some(beamLeg) =>
                        oldTravelTimes += leg.getAttributes.getAttribute("travel_time").toString.toLong.toDouble
                        newTravelTimes += beamLeg.duration.toDouble

                        val javaLinkIds = beamLeg.travelPath.linkIds
                          .map(beamServices.networkHelper.getLinkUnsafe)
                          .map(_.getId)
                          .asJava
                        val newRoute = RouteUtils
                          .createNetworkRoute(javaLinkIds, beamServices.matsimServices.getScenario.getNetwork)
                        leg.setRoute(newRoute)
                        leg.setDepartureTime(beamLeg.startTime)
                        leg.setTravelTime(0)
                        leg.getAttributes.putAttribute("travel_time", beamLeg.duration)
                        leg.getAttributes.putAttribute("departure_time", beamLeg.startTime);
                      case _ =>
                    }
                  }
                )
              case other => throw new IllegalStateException(s"Did not expect to see type ${other.getClass}: $other")
            }
        }
    }
  }

  private def getNewRoutes(
    toReroute: Vector[Person],
    personToRoutes: Vector[(Person, Vector[ElementIndexToLeg])],
    travelTime: TravelTime
  ): Seq[(Person, Vector[ElementIndexToRoutingResponse])] = {
    val r5Wrapper = new R5Wrapper(workerParams, travelTime, 0)
    ProfilingUtils.timed(s"Get new routes for ${toReroute.size} people", x => logger.info(x)) {
      personToRoutes.par.map {
        case (person, xs) =>
          reroute(r5Wrapper, person, xs)
      }.seq
    }
  }

  def printRouteStats(str: String, population: Population): RerouteStats = {
    val routes = population.getPersons.values.asScala.flatMap { person =>
      person.getSelectedPlan.getPlanElements.asScala.collect {
        case leg: Leg if Option(leg.getRoute).nonEmpty && leg.getRoute.isInstanceOf[NetworkRoute] =>
          leg.getRoute.asInstanceOf[NetworkRoute]
      }
    }
    val totalRouteLen = routes.map { route =>
      // route.getLinkIds does not contain start and end links, so we should compute them separately
      val startAndEndLen = beamServices.networkHelper
        .getLinkUnsafe(route.getStartLinkId.toString.toInt)
        .getLength + beamServices.networkHelper.getLinkUnsafe(route.getEndLinkId.toString.toInt).getLength
      val linkLength = route.getLinkIds.asScala.foldLeft(0.0) {
        case (acc, curr) =>
          acc + beamServices.networkHelper.getLinkUnsafe(curr.toString.toInt).getLength
      }
      startAndEndLen + linkLength
    }.sum

    val totalLinkCount = routes.map { route =>
      val constantToCompensateRouteAbsenceOfStartAndEndLinks = 2
      constantToCompensateRouteAbsenceOfStartAndEndLinks + route.getLinkIds.size()
    }.sum

    val avgRouteLen = totalRouteLen / routes.size
    val avgLinkCount = totalLinkCount / routes.size
    logger.info(s"""$str.
                   |Number of routes: ${routes.size}
                   |Total route length: $totalRouteLen
                   |Avg route length: $avgRouteLen
                   |Total link count: $totalLinkCount
                   |Avg link count: $avgLinkCount""".stripMargin)
    RerouteStats(routes.size, totalRouteLen, totalLinkCount)
  }

  private def reroute(
    r5: R5Wrapper,
    person: Person,
    elemIdxToRoute: Vector[ElementIndexToLeg]
  ): (Person, Vector[ElementIndexToRoutingResponse]) = {
    val car = new BeamVehicle(
      BeamVehicle.createId(person.getId, Some("car")),
      new Powertrain(carVehType.primaryFuelConsumptionInJoulePerMeter),
      carVehType
    )

    val idxToResponse = elemIdxToRoute.map {
      case ElementIndexToLeg(idx, leg) =>
        val route = leg.getRoute
        // Do we need to snap it to R5 edge?
        val startCoord = getR5UtmCoord(route.getStartLinkId.toString.toInt)
        val endCoord = getR5UtmCoord(route.getEndLinkId.toString.toInt)

        val departTime = leg.getDepartureTime.toInt
        val currentPointUTM = SpaceTime(startCoord, departTime)
        val carStreetVeh =
          StreetVehicle(
            car.id,
            car.beamVehicleType.id,
            currentPointUTM,
            CAR,
            asDriver = true
          )
        val streetVehicles = Vector(carStreetVeh)
        val maybeAttributes: Option[AttributesOfIndividual] =
          Option(person.getCustomAttributes.get("beam-attributes").asInstanceOf[AttributesOfIndividual])
        val routingRequest = RoutingRequest(
          originUTM = startCoord,
          destinationUTM = endCoord,
          departureTime = departTime,
          withTransit = false,
          streetVehicles = streetVehicles,
          attributesOfIndividual = maybeAttributes,
          streetVehiclesUseIntermodalUse = Access
        )
        val maybeRoutingResponse = Try(r5.calcRoute(routingRequest))
        ElementIndexToRoutingResponse(idx, maybeRoutingResponse)
    }
    person -> idxToResponse
  }

  private def getR5UtmCoord(linkId: Int): Coord = {
    val r5EdgeCoord = beamServices.geo.coordOfR5Edge(beamServices.beamScenario.transportNetwork.streetLayer, linkId)
    beamServices.geo.wgs2Utm(r5EdgeCoord)
  }
}
