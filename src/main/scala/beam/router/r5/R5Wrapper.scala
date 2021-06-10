package beam.router.r5

import beam.agentsim.agents.choice.mode.DrivingCost
import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{RoutingRequest, RoutingResponse, _}
import beam.router.Modes.BeamMode.WALK
import beam.router.Modes.{mapLegMode, toR5StreetMode, BeamMode}
import beam.router.RoutingWorker.{createBushwackingBeamLeg, R5Request, StopVisitor}
import beam.router.gtfs.FareCalculator.{filterFaresOnTransfers, BeamFareSegment}
import beam.router.model.BeamLeg.dummyLeg
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model._
import beam.router.{Modes, Router, RoutingWorker}
import beam.sim.metrics.{Metrics, MetricsSupport}
import com.conveyal.r5.analyst.fare.SimpleInRoutingFareCalculator
import com.conveyal.r5.api.ProfileResponse
import com.conveyal.r5.api.util._
import com.conveyal.r5.profile.{McRaptorSuboptimalPathProfileRouter, ProfileRequest, StreetMode, StreetPath}
import com.conveyal.r5.streets._
import com.conveyal.r5.transit.TransitLayer
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.router.util.TravelTime
import org.matsim.vehicles.Vehicle

import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicInteger
import java.util.{Collections, Optional}
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.language.postfixOps
import scala.util.Try

class R5Wrapper(workerParams: R5Parameters, travelTime: TravelTime, travelTimeNoiseFraction: Double)
    extends MetricsSupport
    with StrictLogging
    with Router {
  private val maxDistanceForBikeMeters: Int =
    workerParams.beamConfig.beam.routing.r5.maxDistanceLimitByModeInMeters.bike

  private val R5Parameters(
    beamConfig,
    transportNetwork,
    vehicleTypes,
    fuelTypePrices,
    ptFares,
    geo,
    dates,
    networkHelper,
    fareCalculator,
    tollCalculator
  ) = workerParams

  private val carWeightCalculator = new CarWeightCalculator(workerParams, travelTimeNoiseFraction)
  private val bikeLanesAdjustment = BikeLanesAdjustment(beamConfig)

  def embodyWithCurrentTravelTime(
    leg: BeamLeg,
    vehicleId: Id[Vehicle],
    vehicleTypeId: Id[BeamVehicleType],
    embodyRequestId: Int,
    triggerId: Long
  ): RoutingResponse = {
    val vehicleType = vehicleTypes(vehicleTypeId)
    val linksTimesAndDistances = RoutingModel.linksToTimeAndDistance(
      leg.travelPath.linkIds,
      leg.startTime,
      travelTimeByLinkCalculator(vehicleType, shouldAddNoise = false),
      toR5StreetMode(leg.mode),
      transportNetwork.streetLayer
    )
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val startLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesAndDistances.linkIds.head)
    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val endLoc = geo.coordOfR5Edge(transportNetwork.streetLayer, linksTimesAndDistances.linkIds.last)
    val duration = linksTimesAndDistances.travelTimes.tail.sum
    val updatedTravelPath = BeamPath(
      linksTimesAndDistances.linkIds,
      linksTimesAndDistances.travelTimes,
      None,
      SpaceTime(startLoc.getX, startLoc.getY, leg.startTime),
      SpaceTime(
        endLoc.getX,
        endLoc.getY,
        leg.startTime + Math.round(duration.toFloat)
      ),
      distanceInM = linksTimesAndDistances.distances.tail.sum
    )
    val toll = tollCalculator.calcTollByLinkIds(updatedTravelPath)
    val updatedLeg = leg.copy(travelPath = updatedTravelPath, duration = updatedTravelPath.duration)
    val drivingCost = DrivingCost.estimateDrivingCost(
      updatedLeg.travelPath.distanceInM,
      updatedLeg.duration,
      vehicleType,
      fuelTypePrices(vehicleType.primaryFuelType)
    )
    val totalCost = drivingCost + (if (updatedLeg.mode == BeamMode.CAR) toll else 0)
    val response = RoutingResponse(
      Vector(
        EmbodiedBeamTrip(
          Vector(
            EmbodiedBeamLeg(
              updatedLeg,
              vehicleId,
              vehicleTypeId,
              asDriver = true,
              totalCost,
              unbecomeDriverOnCompletion = true
            )
          )
        )
      ),
      embodyRequestId,
      None,
      isEmbodyWithCurrentTravelTime = true,
      triggerId
    )
    response
  }

  private def getStreetPlanFromR5(request: R5Request): ProfileResponse = {
    countOccurrence("r5-plans-count", request.time)

    val profileRequest = createProfileRequestFromRequest(request)
    try {
      val profileResponse = new ProfileResponse
      val directOption = new ProfileOption
      profileRequest.reverseSearch = false
      for (mode <- profileRequest.directModes.asScala) {

        val streetRouter = new StreetRouter(
          transportNetwork.streetLayer,
          travelTimeCalculator(
            vehicleTypes(request.beamVehicleTypeId),
            profileRequest.fromTime,
            shouldAddNoise = !profileRequest.hasTransit
          ), // Add error if it is not transit
          turnCostCalculator,
          travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
        )
        if (request.accessMode == LegMode.BICYCLE) {
          streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
        }
        streetRouter.profileRequest = profileRequest
        streetRouter.streetMode = toR5StreetMode(mode)
        streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
        if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
          if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
            latency("route-transit-time", Metrics.VerboseLevel) {
              streetRouter.route() // latency 1
            }
            val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
            if (lastState != null) {
              val streetPath = new StreetPath(lastState, transportNetwork, false)
              val streetSegment = new StreetSegment(streetPath, mode, transportNetwork.streetLayer)
              directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
            }
          }
        }
      }
      directOption.summary = directOption.generateSummary
      profileResponse.addOption(directOption)
      profileResponse.recomputeStats(profileRequest)
      profileResponse
    } catch {
      case _: IllegalStateException =>
        new ProfileResponse
      case _: ArrayIndexOutOfBoundsException =>
        new ProfileResponse
    }
  }

  private def createProfileRequestFromRequest(request: R5Request) = {
    val result = createProfileRequest
    result.fromLon = request.from.getX
    result.fromLat = request.from.getY
    result.toLon = request.to.getX
    result.toLat = request.to.getY
    result.fromTime = request.time
    result.toTime = request.time + 61 // Important to allow 61 seconds for transit schedules to be considered!
    result.directModes = if (request.directMode == null) {
      util.EnumSet.noneOf(classOf[LegMode])
    } else {
      util.EnumSet.of(request.directMode)
    }
    result
  }

  private def createProfileRequest = {
    val profileRequest = new ProfileRequest()
    // Warning: carSpeed is not used for link traversal (rather, the OSM travel time model is used),
    // but for R5-internal bushwhacking from network to coordinate, AND ALSO for the A* remaining weight heuristic,
    // which means that this value must be an over(!)estimation, otherwise we will miss optimal routes,
    // particularly in the presence of tolls.
    profileRequest.carSpeed = carWeightCalculator.maxFreeSpeed.toFloat
    profileRequest.maxWalkTime = 30
    profileRequest.maxCarTime = 30
    profileRequest.maxBikeTime = 30
    // Maximum number of transit segments. This was previously hardcoded as 4 in R5, now it is a parameter
    // that defaults to 8 unless I reset it here. It is directly related to the amount of work the
    // transit router has to do.
    profileRequest.maxRides = 4
    profileRequest.streetTime = 2 * 60
    profileRequest.maxTripDurationMinutes = 4 * 60
    profileRequest.wheelchair = false
    profileRequest.bikeTrafficStress = 4
    profileRequest.zoneId = transportNetwork.getTimeZone
    profileRequest.monteCarloDraws = beamConfig.beam.routing.r5.numberOfSamples
    profileRequest.date = dates.localBaseDate
    // Doesn't calculate any fares, is just a no-op placeholder
    profileRequest.inRoutingFareCalculator = new SimpleInRoutingFareCalculator
    profileRequest.suboptimalMinutes = 0
    profileRequest
  }

  def calcRoute(
    request: RoutingRequest,
    buildDirectCarRoute: Boolean,
    buildDirectWalkRoute: Boolean
  ): RoutingResponse = {
    // For each street vehicle (including body, if available): Route from origin to street vehicle, from street vehicle to destination.
    val isRouteForPerson = request.streetVehicles.exists(_.mode == WALK)

    def calcRouteToVehicle(vehicle: StreetVehicle): Option[EmbodiedBeamLeg] = {
      val mainRouteFromVehicle = request.streetVehiclesUseIntermodalUse == Access && isRouteForPerson && vehicle.mode != WALK
      if (mainRouteFromVehicle) {
        val body = request.streetVehicles.find(_.mode == WALK).get
        if (geo.distUTMInMeters(vehicle.locationUTM.loc, request.originUTM) > beamConfig.beam.agentsim.thresholdForWalkingInMeters) {
          val fromWgs = geo.snapToR5Edge(
            transportNetwork.streetLayer,
            geo.utm2Wgs(request.originUTM),
            10E3
          )
          val toWgs = geo.snapToR5Edge(
            transportNetwork.streetLayer,
            geo.utm2Wgs(vehicle.locationUTM.loc),
            10E3
          )
          val directMode = LegMode.WALK
          val accessMode = LegMode.WALK
          val egressMode = LegMode.WALK
          val profileResponse =
            latency("walkToVehicleRoute-router-time", Metrics.RegularLevel) {
              getStreetPlanFromR5(
                R5Request(
                  fromWgs,
                  toWgs,
                  request.departureTime,
                  directMode,
                  accessMode,
                  withTransit = false,
                  egressMode,
                  request.timeValueOfMoney,
                  body.vehicleTypeId
                )
              )
            }
          if (profileResponse.options.isEmpty) {
            Some(
              EmbodiedBeamLeg(
                createBushwackingBeamLeg(request.departureTime, request.originUTM, vehicle.locationUTM.loc, geo),
                body.id,
                body.vehicleTypeId,
                asDriver = true,
                0,
                unbecomeDriverOnCompletion = false
              )
            )
          } else {
            val streetSegment = profileResponse.options.get(0).access.get(0)
            Some(
              buildStreetBasedLegs(
                streetSegment,
                request.departureTime,
                body,
                unbecomeDriverOnCompletion = false
              )
            )
          }
        } else {
          Some(
            EmbodiedBeamLeg(
              dummyLeg(request.departureTime, geo.utm2Wgs(vehicle.locationUTM.loc)),
              body.id,
              body.vehicleTypeId,
              body.asDriver,
              0.0,
              unbecomeDriverOnCompletion = false
            )
          )
        }
      } else {
        None
      }
    }

    def routeFromVehicleToDestination(vehicle: StreetVehicle) = {
      // assume 13 mph / 5.8 m/s as average PT speed: http://cityobservatory.org/urban-buses-are-slowing-down/
      val estimateDurationToGetToVeh: Int = math
        .round(geo.distUTMInMeters(request.originUTM, vehicle.locationUTM.loc) / 5.8)
        .intValue()
      val time = request.departureTime + estimateDurationToGetToVeh
      val fromWgs = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(vehicle.locationUTM.loc),
        10E3
      )
      val toWgs = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(request.destinationUTM),
        10E3
      )
      val vehicleLegMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
      val profileResponse =
        latency("vehicleOnEgressRoute-router-time", Metrics.RegularLevel) {
          getStreetPlanFromR5(
            R5Request(
              fromWgs,
              toWgs,
              time,
              directMode = vehicleLegMode,
              accessMode = vehicleLegMode,
              withTransit = false,
              egressMode = LegMode.WALK,
              request.timeValueOfMoney,
              vehicle.vehicleTypeId
            )
          )
        }
      if (!profileResponse.options.isEmpty) {
        val streetSegment = profileResponse.options.get(0).access.get(0)
        buildStreetBasedLegs(
          streetSegment,
          time,
          vehicle,
          unbecomeDriverOnCompletion = true
        )
      } else {
        EmbodiedBeamLeg(
          createBushwackingBeamLeg(request.departureTime, vehicle.locationUTM.loc, request.destinationUTM, geo),
          vehicle.id,
          vehicle.vehicleTypeId,
          asDriver = true,
          0,
          unbecomeDriverOnCompletion = true
        )
      }
    }

    /*
     * Our algorithm captures a few different patterns of travel. Two of these require extra routing beyond what we
     * call the "main" route calculation below. In both cases, we have a single main transit route
     * which is only calculate once in the code below. But we optionally add a WALK leg from the origin to the
     * beginning of the route (called "mainRouteFromVehicle" as opposed to main route from origin). Or we optionally
     * add a vehicle-based trip on the egress portion of the trip (called "mainRouteToVehicle" as opposed to main route
     * to destination).
     *
     * Or we use the R5 egress concept to accomplish "mainRouteRideHailTransit" pattern we use DRIVE mode on both
     * access and egress legs. For the other "mainRoute" patterns, we want to fix the location of the vehicle, not
     * make it dynamic. Also note that in all cases, these patterns are only the result of human travelers, we assume
     * AI is fixed to a vehicle and therefore only needs the simplest of routes.
     *
     * For the mainRouteFromVehicle pattern, the traveler is using a vehicle within the context of a
     * trip that could be multimodal (e.g. drive to transit) or unimodal (drive only). We don't assume the vehicle is
     * co-located with the person, so this first block of code determines the distance from the vehicle to the person and based
     * on a threshold, optionally routes a WALK leg to the vehicle and adjusts the main route location & time accordingly.
     *
     */
    val mainRouteToVehicle = request.streetVehiclesUseIntermodalUse == Egress && isRouteForPerson
    val mainRouteRideHailTransit = request.streetVehiclesUseIntermodalUse == AccessAndEgress && isRouteForPerson

    val profileRequest = createProfileRequest
    val accessVehicles = if (mainRouteToVehicle) {
      Vector(request.streetVehicles.find(_.mode == WALK).get)
    } else if (mainRouteRideHailTransit) {
      request.streetVehicles.filter(_.mode != WALK)
    } else {
      request.streetVehicles
    }

    val maybeWalkToVehicle: Map[StreetVehicle, Option[EmbodiedBeamLeg]] =
      accessVehicles.map(v => v -> calcRouteToVehicle(v)).toMap

    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val bestAccessVehiclesByR5Mode: Map[LegMode, StreetVehicle] = accessVehicles
      .groupBy(_.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf("")))
      .mapValues(vehicles => vehicles.minBy(maybeWalkToVehicle(_).map(leg => leg.beamLeg.duration).getOrElse(0)))

    val egressVehicles = if (mainRouteRideHailTransit) {
      request.streetVehicles.filter(_.mode != WALK)
    } else if (request.withTransit) {
      request.possibleEgressVehicles :+ request.streetVehicles.find(_.mode == WALK).get
    } else {
      Vector()
    }
    val destinationVehicles = if (mainRouteToVehicle) {
      request.streetVehicles.filter(_.mode != WALK)
    } else {
      Vector()
    }
    if (request.withTransit) {
      profileRequest.transitModes = util.EnumSet.allOf(classOf[TransitModes])
    }

    val destinationVehicle = destinationVehicles.headOption
    val vehicleToDestinationLeg = destinationVehicle.map(v => routeFromVehicleToDestination(v))

    val accessRouters = mutable.Map[LegMode, StreetRouter]()
    val accessStopsByMode = mutable.Map[LegMode, StopVisitor]()
    val profileResponse = new ProfileResponse
    val directOption = new ProfileOption
    profileRequest.reverseSearch = false
    for (vehicle <- bestAccessVehiclesByR5Mode.values) {
      val theOrigin = if (mainRouteToVehicle || mainRouteRideHailTransit) {
        request.originUTM
      } else {
        vehicle.locationUTM.loc
      }
      val theDestination = if (mainRouteToVehicle) {
        destinationVehicle.get.locationUTM.loc
      } else {
        request.destinationUTM
      }
      val from = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(theOrigin),
        10E3
      )
      val to = geo.snapToR5Edge(
        transportNetwork.streetLayer,
        geo.utm2Wgs(theDestination),
        10E3
      )
      profileRequest.fromLon = from.getX
      profileRequest.fromLat = from.getY
      profileRequest.toLon = to.getX
      profileRequest.toLat = to.getY
      val walkToVehicleDuration = maybeWalkToVehicle(vehicle).map(leg => leg.beamLeg.duration).getOrElse(0)
      profileRequest.fromTime = request.departureTime + walkToVehicleDuration
      profileRequest.toTime = profileRequest.fromTime + 61 // Important to allow 61 seconds for transit schedules to be considered!
      val streetRouter = new StreetRouter(
        transportNetwork.streetLayer,
        travelTimeCalculator(
          vehicleTypes(vehicle.vehicleTypeId),
          profileRequest.fromTime,
          shouldAddNoise = !profileRequest.hasTransit
        ),
        turnCostCalculator,
        travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
      )
      if (vehicle.mode == BeamMode.BIKE) {
        streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
      }
      streetRouter.profileRequest = profileRequest
      streetRouter.streetMode = toR5StreetMode(vehicle.mode)
      val legMode: LegMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
      val calcDirectRoute = legMode match {
        case LegMode.WALK => buildDirectWalkRoute
        case LegMode.CAR  => buildDirectCarRoute
        case _            => true
      }
      if (streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)) {
        if (profileRequest.hasTransit) {
          val destinationSplit = transportNetwork.streetLayer.findSplit(
            profileRequest.toLat,
            profileRequest.toLon,
            StreetLayer.LINK_RADIUS_METERS,
            streetRouter.streetMode
          )
          val stopVisitor = new StopVisitor(
            transportNetwork.streetLayer,
            streetRouter.quantityToMinimize,
            streetRouter.transitStopSearchQuantity,
            profileRequest.getMinTimeLimit(streetRouter.streetMode),
            destinationSplit
          )
          streetRouter.setRoutingVisitor(stopVisitor)
          streetRouter.timeLimitSeconds = profileRequest.getTimeLimit(legMode)
          streetRouter.route()
          accessRouters.put(legMode, streetRouter)
          accessStopsByMode.put(legMode, stopVisitor)
          if (calcDirectRoute && !mainRouteRideHailTransit) {
            // Not interested in direct options in the ride-hail-transit case,
            // only in the option where we actually use non-empty ride-hail for access and egress.
            // This is only for saving a computation, and only because the requests are structured like they are.
            if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
              val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
              if (lastState != null) {
                val streetPath = new StreetPath(lastState, transportNetwork, false)
                val streetSegment =
                  new StreetSegment(streetPath, legMode, transportNetwork.streetLayer)
                directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
              } else if (profileRequest.streetTime * 60 > streetRouter.timeLimitSeconds) {
                val streetRouter = new StreetRouter(
                  transportNetwork.streetLayer,
                  travelTimeCalculator(
                    vehicleTypes(vehicle.vehicleTypeId),
                    profileRequest.fromTime,
                    shouldAddNoise = !profileRequest.hasTransit
                  ),
                  turnCostCalculator,
                  travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
                )
                if (vehicle.mode == BeamMode.BIKE) {
                  streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
                }
                streetRouter.profileRequest = profileRequest
                streetRouter.streetMode = toR5StreetMode(vehicle.mode)
                streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
                streetRouter.setOrigin(profileRequest.fromLat, profileRequest.fromLon)
                streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)
                streetRouter.route()
                val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
                if (lastState != null) {
                  val streetPath = new StreetPath(lastState, transportNetwork, false)
                  val streetSegment =
                    new StreetSegment(streetPath, legMode, transportNetwork.streetLayer)
                  directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
                }
              }
            }
          }
        } else if (calcDirectRoute && !mainRouteRideHailTransit) {
          streetRouter.timeLimitSeconds = profileRequest.streetTime * 60
          if (streetRouter.setDestination(profileRequest.toLat, profileRequest.toLon)) {
            streetRouter.route()
            val lastState = streetRouter.getState(streetRouter.getDestinationSplit)
            if (lastState != null) {
              val streetPath = new StreetPath(lastState, transportNetwork, false)
              val streetSegment =
                new StreetSegment(streetPath, legMode, transportNetwork.streetLayer)
              directOption.addDirect(streetSegment, profileRequest.getFromTimeDateZD)
            }
          }
        }
      }
    }
    directOption.summary = directOption.generateSummary
    profileResponse.addOption(directOption)

    if (profileRequest.hasTransit) {
      val egressRouters = mutable.Map[LegMode, StreetRouter]()
      val egressStopsByMode = mutable.Map[LegMode, StopVisitor]()
      profileRequest.reverseSearch = true
      for (vehicle <- egressVehicles) {
        val theDestination = if (mainRouteToVehicle) {
          destinationVehicle.get.locationUTM.loc
        } else {
          request.destinationUTM
        }
        val to = geo.snapToR5Edge(
          transportNetwork.streetLayer,
          geo.utm2Wgs(theDestination),
          10E3
        )
        profileRequest.toLon = to.getX
        profileRequest.toLat = to.getY
        val streetRouter = new StreetRouter(
          transportNetwork.streetLayer,
          travelTimeCalculator(
            vehicleTypes(vehicle.vehicleTypeId),
            profileRequest.fromTime,
            shouldAddNoise = !profileRequest.hasTransit
          ),
          turnCostCalculator,
          travelCostCalculator(request.timeValueOfMoney, profileRequest.fromTime)
        )
        if (vehicle.mode == BeamMode.BIKE) {
          streetRouter.distanceLimitMeters = maxDistanceForBikeMeters
        }
        val legMode = vehicle.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf(""))
        streetRouter.streetMode = toR5StreetMode(vehicle.mode)
        streetRouter.profileRequest = profileRequest
        streetRouter.timeLimitSeconds = profileRequest.getTimeLimit(legMode)
        val destinationSplit = transportNetwork.streetLayer.findSplit(
          profileRequest.fromLat,
          profileRequest.fromLon,
          StreetLayer.LINK_RADIUS_METERS,
          streetRouter.streetMode
        )
        val stopVisitor = new StopVisitor(
          transportNetwork.streetLayer,
          streetRouter.quantityToMinimize,
          streetRouter.transitStopSearchQuantity,
          profileRequest.getMinTimeLimit(streetRouter.streetMode),
          destinationSplit
        )
        streetRouter.setRoutingVisitor(stopVisitor)
        if (streetRouter.setOrigin(profileRequest.toLat, profileRequest.toLon)) {
          streetRouter.route()
          egressRouters.put(legMode, streetRouter)
          egressStopsByMode.put(legMode, stopVisitor)
        }
      }

      val transitPaths = latency("getpath-transit-time", Metrics.VerboseLevel) {
        profileRequest.fromTime = request.departureTime
        profileRequest.toTime = request.departureTime + 61 // Important to allow 61 seconds for transit schedules to be considered!
        val router = new McRaptorSuboptimalPathProfileRouter(
          transportNetwork,
          profileRequest,
          accessStopsByMode.mapValues(_.stops).asJava,
          egressStopsByMode.mapValues(_.stops).asJava,
          (departureTime: Int) =>
            new BeamDominatingList(
              profileRequest.inRoutingFareCalculator,
              Integer.MAX_VALUE,
              departureTime + profileRequest.maxTripDurationMinutes * 60
          ),
          null
        )
        Try(router.getPaths.asScala).getOrElse(Nil) // Catch IllegalStateException in R5.StatsCalculator
      }

      for (transitPath <- transitPaths) {
        profileResponse.addTransitPath(
          accessRouters.asJava,
          egressRouters.asJava,
          transitPath,
          transportNetwork,
          profileRequest.getFromTimeDateZD
        )
      }

      latency("transfer-transit-time", Metrics.VerboseLevel) {
        profileResponse.generateStreetTransfers(transportNetwork, profileRequest)
      }
    }
    profileResponse.recomputeStats(profileRequest)

    val embodiedTrips = profileResponse.options.asScala.flatMap { option =>
      option.itinerary.asScala
        .map { itinerary =>
          // Using itinerary start as access leg's startTime
          val tripStartTime = dates
            .toBaseMidnightSeconds(
              itinerary.startTime,
              transportNetwork.transitLayer.routes.size() == 0
            )
            .toInt

          var arrivalTime: Int = Int.MinValue
          val embodiedBeamLegs = mutable.ArrayBuffer.empty[EmbodiedBeamLeg]
          val access = option.access.get(itinerary.connection.access)
          val vehicle = bestAccessVehiclesByR5Mode(access.mode)
          maybeWalkToVehicle(vehicle).foreach(walkLeg => {
            // Glue the walk to vehicle in front of the trip without a gap
            embodiedBeamLegs += walkLeg
              .copy(beamLeg = walkLeg.beamLeg.updateStartTime(tripStartTime - walkLeg.beamLeg.duration))
          })

          embodiedBeamLegs += buildStreetBasedLegs(
            access,
            tripStartTime,
            vehicle,
            unbecomeDriverOnCompletion = access.mode != LegMode.WALK || option.transit == null
          )

          arrivalTime = embodiedBeamLegs.last.beamLeg.endTime

          val transitSegments = Optional.ofNullable(option.transit).orElse(Collections.emptyList()).asScala
          val transitJourneyIDs =
            Optional.ofNullable(itinerary.connection.transit).orElse(Collections.emptyList()).asScala
          // Based on "Index in transit list specifies transit with same index" (comment from PointToPointConnection line 14)
          // assuming that: For each transit in option there is a TransitJourneyID in connection
          val segments = transitSegments zip transitJourneyIDs

          // Lazy because this looks expensive and we may not need it because there's _another_ fare
          // calculation that takes precedence
          lazy val fares = latency("fare-transit-time", Metrics.VerboseLevel) {
            val fareSegments = getFareSegments(segments.toVector)
            filterFaresOnTransfers(fareSegments)
          }
          segments.foreach {
            case (transitSegment, transitJourneyID) =>
              val segmentPattern = transitSegment.segmentPatterns.get(transitJourneyID.pattern)
              val tripPattern = profileResponse.getPatterns.asScala
                .find { tp =>
                  tp.getTripPatternIdx == segmentPattern.patternIdx
                }
                .getOrElse(throw new RuntimeException())
              val tripId = segmentPattern.tripIds.get(transitJourneyID.time)
              val route = transportNetwork.transitLayer.routes.get(tripPattern.getRouteIdx)
              val fromStop = tripPattern.getStops.get(segmentPattern.fromIndex)
              val toStop = tripPattern.getStops.get(segmentPattern.toIndex)
              val startTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.fromDepartureTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              val endTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.toArrivalTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              val stopSequence =
                tripPattern.getStops.asScala.toList.slice(segmentPattern.fromIndex, segmentPattern.toIndex + 1)
              val segmentLeg = BeamLeg(
                startTime,
                Modes.mapTransitMode(TransitLayer.getTransitModes(route.route_type)),
                java.time.temporal.ChronoUnit.SECONDS
                  .between(
                    segmentPattern.fromDepartureTime.get(transitJourneyID.time),
                    segmentPattern.toArrivalTime.get(transitJourneyID.time)
                  )
                  .toInt,
                BeamPath(
                  Vector(),
                  Vector(),
                  Some(
                    TransitStopsInfo(
                      route.agency_id,
                      tripPattern.getRouteId,
                      Id.createVehicleId(tripId),
                      segmentPattern.fromIndex,
                      segmentPattern.toIndex
                    )
                  ),
                  SpaceTime(fromStop.lon, fromStop.lat, startTime),
                  SpaceTime(toStop.lon, toStop.lat, endTime),
                  stopSequence.sliding(2).map(x => getDistanceBetweenStops(x.head, x.last)).sum
                )
              )
              embodiedBeamLegs += EmbodiedBeamLeg(
                segmentLeg,
                segmentLeg.travelPath.transitStops.get.vehicleId,
                null,
                asDriver = false,
                ptFares
                  .getPtFare(
                    Some(segmentLeg.travelPath.transitStops.get.agencyId),
                    Some(segmentLeg.travelPath.transitStops.get.routeId),
                    request.attributesOfIndividual.flatMap(_.age)
                  )
                  .getOrElse {
                    val fs =
                      fares.view
                        .filter(_.patternIndex == segmentPattern.patternIdx)
                        .map(_.fare.price)
                    if (fs.nonEmpty) fs.min else 0.0
                  },
                unbecomeDriverOnCompletion = false
              )
              arrivalTime = dates
                .toBaseMidnightSeconds(
                  segmentPattern.toArrivalTime.get(transitJourneyID.time),
                  hasTransit = true
                )
                .toInt
              if (transitSegment.middle != null) {
                val body = request.streetVehicles.find(_.mode == WALK).get
                embodiedBeamLegs += buildStreetBasedLegs(
                  transitSegment.middle,
                  arrivalTime,
                  body,
                  unbecomeDriverOnCompletion = false
                )
                arrivalTime = arrivalTime + transitSegment.middle.duration
              }
          }

          if (itinerary.connection.egress != null) {
            val egress = option.egress.get(itinerary.connection.egress)
            val vehicle =
              egressVehicles
                .find(v => v.mode.r5Mode.flatMap(_.left.toOption).getOrElse(LegMode.valueOf("")) == egress.mode)
                .get
            embodiedBeamLegs += buildStreetBasedLegs(
              egress,
              arrivalTime,
              vehicle,
              unbecomeDriverOnCompletion = true
            )
            val body = request.streetVehicles.find(_.mode == WALK).get
            if (isRouteForPerson && egress.mode != LegMode.WALK) {
              embodiedBeamLegs += EmbodiedBeamLeg(
                dummyLeg(arrivalTime + egress.duration, embodiedBeamLegs.last.beamLeg.travelPath.endPoint.loc),
                body.id,
                body.vehicleTypeId,
                body.asDriver,
                0.0,
                unbecomeDriverOnCompletion = true
              )
            }
          }

          vehicleToDestinationLeg.foreach { legWithFare =>
            // Glue the drive to the final destination behind the trip without a gap
            embodiedBeamLegs += legWithFare.copy(
              beamLeg = legWithFare.beamLeg.updateStartTime(embodiedBeamLegs.last.beamLeg.endTime)
            )
          }
          if (isRouteForPerson && embodiedBeamLegs.last.beamLeg.mode != WALK) {
            val body = request.streetVehicles.find(_.mode == WALK).get
            embodiedBeamLegs += EmbodiedBeamLeg(
              dummyLeg(embodiedBeamLegs.last.beamLeg.endTime, embodiedBeamLegs.last.beamLeg.travelPath.endPoint.loc),
              body.id,
              body.vehicleTypeId,
              body.asDriver,
              0.0,
              unbecomeDriverOnCompletion = true
            )
          }
          EmbodiedBeamTrip(embodiedBeamLegs)
        }
        .filter { trip: EmbodiedBeamTrip =>
          //TODO make a more sensible window not just 30 minutes
          trip.legs.head.beamLeg.startTime >= request.departureTime && trip.legs.head.beamLeg.startTime <= request.departureTime + 1800
        }
    }

    val routingResponse = if (!embodiedTrips.exists(_.tripClassifier == WALK) && !mainRouteToVehicle) {
      val maybeBody = accessVehicles.find(_.mode == WALK)
      if (buildDirectWalkRoute && maybeBody.isDefined) {
        val dummyTrip = RoutingWorker.createBushwackingTrip(
          new Coord(request.originUTM.getX, request.originUTM.getY),
          new Coord(request.destinationUTM.getX, request.destinationUTM.getY),
          request.departureTime,
          maybeBody.get,
          geo
        )
        RoutingResponse(
          embodiedTrips :+ dummyTrip,
          request.requestId,
          Some(request),
          isEmbodyWithCurrentTravelTime = false,
          request.triggerId
        )
      } else {
        RoutingResponse(
          embodiedTrips,
          request.requestId,
          Some(request),
          isEmbodyWithCurrentTravelTime = false,
          request.triggerId
        )
      }
    } else {
      RoutingResponse(
        embodiedTrips,
        request.requestId,
        Some(request),
        isEmbodyWithCurrentTravelTime = false,
        request.triggerId
      )
    }

    routingResponse
  }

  private def getDistanceBetweenStops(fromStop: Stop, toStop: Stop): Double = {
    geo.distLatLon2Meters(new Coord(fromStop.lon, fromStop.lat), new Coord(toStop.lon, toStop.lat))
  }

  private def buildStreetBasedLegs(
    segment: StreetSegment,
    tripStartTime: Int,
    vehicle: StreetVehicle,
    unbecomeDriverOnCompletion: Boolean
  ): EmbodiedBeamLeg = {
    val startPoint = SpaceTime(
      segment.geometry.getStartPoint.getX,
      segment.geometry.getStartPoint.getY,
      tripStartTime
    )
    val endCoord = new Coord(
      segment.geometry.getEndPoint.getX,
      segment.geometry.getEndPoint.getY,
    )

    var activeLinkIds = ArrayBuffer[Int]()
    for (edge: StreetEdgeInfo <- segment.streetEdges.asScala) {
      activeLinkIds += edge.edgeId.intValue()
    }
    val beamLeg: BeamLeg =
      createBeamLeg(vehicle.vehicleTypeId, startPoint, endCoord, segment.mode, activeLinkIds)
    val toll = if (segment.mode == LegMode.CAR) {
      val osm = segment.streetEdges.asScala
        .map(
          e =>
            transportNetwork.streetLayer.edgeStore
              .getCursor(e.edgeId)
              .getOSMID
        )
        .toVector
      tollCalculator.calcTollByOsmIds(osm) + tollCalculator.calcTollByLinkIds(beamLeg.travelPath)
    } else 0.0
    val drivingCost = if (segment.mode == LegMode.CAR || vehicle.needsToCalculateCost) {
      val vehicleType = vehicleTypes(vehicle.vehicleTypeId)
      DrivingCost.estimateDrivingCost(
        beamLeg.travelPath.distanceInM,
        beamLeg.duration,
        vehicleType,
        fuelTypePrices(vehicleType.primaryFuelType)
      )
    } else 0.0
    EmbodiedBeamLeg(
      beamLeg,
      vehicle.id,
      vehicle.vehicleTypeId,
      vehicle.asDriver,
      drivingCost + toll,
      unbecomeDriverOnCompletion
    )
  }

  private def createBeamLeg(
    vehicleTypeId: Id[BeamVehicleType],
    startPoint: SpaceTime,
    endCoord: Coord,
    legMode: LegMode,
    activeLinkIds: IndexedSeq[Int]
  ): BeamLeg = {
    val tripStartTime: Int = startPoint.time
    // During routing `travelTimeByLinkCalculator` is used with shouldAddNoise = true (if it is not transit)
    // That trick gives us back diverse route. Now we want to compute travel time per link and we don't want to include that noise
    val linksTimesDistances = RoutingModel.linksToTimeAndDistance(
      activeLinkIds,
      tripStartTime,
      travelTimeByLinkCalculator(vehicleTypes(vehicleTypeId), shouldAddNoise = false), // Do not add noise!
      toR5StreetMode(legMode),
      transportNetwork.streetLayer
    )
    val distance = linksTimesDistances.distances.tail.sum // note we exclude the first link to keep with MATSim convention
    val theTravelPath = BeamPath(
      linkIds = activeLinkIds,
      linkTravelTime = linksTimesDistances.travelTimes,
      transitStops = None,
      startPoint = startPoint,
      endPoint = SpaceTime(endCoord, startPoint.time + math.round(linksTimesDistances.travelTimes.tail.sum.toFloat)),
      distanceInM = distance
    )
    val beamLeg = BeamLeg(
      tripStartTime,
      mapLegMode(legMode),
      theTravelPath.duration,
      travelPath = theTravelPath
    )
    beamLeg
  }

  /**
    * Use to extract a collection of FareSegments for an itinerary.
    *
    * @param segments IndexedSeq[(TransitSegment, TransitJourneyID)]
    * @return a collection of FareSegments for an itinerary.
    */
  private def getFareSegments(
    segments: IndexedSeq[(TransitSegment, TransitJourneyID)]
  ): IndexedSeq[BeamFareSegment] = {
    segments
      .groupBy(s => getRoute(s._1, s._2).agency_id)
      .flatMap(t => {
        val pattern = getPattern(t._2.head._1, t._2.head._2)
        val fromTime = pattern.fromDepartureTime.get(t._2.head._2.time)

        var rules = t._2.flatMap(s => getFareSegments(s._1, s._2, fromTime))

        if (rules.isEmpty) {
          val route = getRoute(pattern)
          val agencyId = route.agency_id
          val routeId = route.route_id

          val fromId = getStopId(t._2.head._1.from)
          val toId = getStopId(t._2.last._1.to)

          val toTime = getPattern(t._2.last._1, t._2.last._2).toArrivalTime
            .get(t._2.last._2.time)
          val duration = ChronoUnit.SECONDS.between(fromTime, toTime)

          val containsIds =
            t._2
              .flatMap(s => IndexedSeq(getStopId(s._1.from), getStopId(s._1.to)))
              .toSet

          rules = getFareSegments(agencyId, routeId, fromId, toId, containsIds)
            .map(f => BeamFareSegment(f, pattern.patternIdx, duration))
        }
        rules
      })
      .toIndexedSeq
  }

  private def getFareSegments(
    transitSegment: TransitSegment,
    transitJourneyID: TransitJourneyID,
    fromTime: ZonedDateTime
  ): IndexedSeq[BeamFareSegment] = {
    val pattern = getPattern(transitSegment, transitJourneyID)
    val route = getRoute(pattern)
    val routeId = route.route_id
    val agencyId = route.agency_id

    val fromStopId = getStopId(transitSegment.from)
    val toStopId = getStopId(transitSegment.to)
    val duration =
      ChronoUnit.SECONDS
        .between(fromTime, pattern.toArrivalTime.get(transitJourneyID.time))

    calculateFr(pattern, routeId, agencyId, fromStopId, toStopId, duration)
  }

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  private def calculateFr(
    pattern: SegmentPattern,
    routeId: String,
    agencyId: String,
    fromStopId: String,
    toStopId: String,
    duration: Long
  ): IndexedSeq[BeamFareSegment] = {
    var fr = getFareSegments(agencyId, routeId, fromStopId, toStopId).map(
      f => BeamFareSegment(f, pattern.patternIdx, duration)
    )
    if (fr.nonEmpty && fr.forall(_.patternIndex == fr.head.patternIndex)) {
      fr = Vector(fr.minBy(_.fare.price))
    }
    fr
  }

  private def getFareSegments(
    agencyId: String,
    routeId: String,
    fromId: String,
    toId: String,
    containsIds: Set[String] = null
  ): IndexedSeq[BeamFareSegment] =
    fareCalculator.getFareSegments(agencyId, routeId, fromId, toId, containsIds)

  private def getRoute(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transportNetwork.transitLayer.routes
      .get(getPattern(transitSegment, transitJourneyID).routeIndex)

  private def getRoute(segmentPattern: SegmentPattern) =
    transportNetwork.transitLayer.routes.get(segmentPattern.routeIndex)

  private def getPattern(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transitSegment.segmentPatterns.get(transitJourneyID.pattern)

  private def getStopId(stop: Stop) = stop.stopId.split(":")(1)

  private def travelTimeCalculator(
    vehicleType: BeamVehicleType,
    startTime: Int,
    shouldAddNoise: Boolean
  ): TravelTimeCalculator = {
    val ttc = travelTimeByLinkCalculator(vehicleType, shouldAddNoise, shouldApplyBicycleScaleFactor = true)
    (edge: EdgeStore#Edge, durationSeconds: Int, streetMode: StreetMode, _) =>
      {
        ttc(startTime + durationSeconds, edge.getEdgeIndex, streetMode).floatValue()
      }
  }

  private def travelTimeByLinkCalculator(
    vehicleType: BeamVehicleType,
    shouldAddNoise: Boolean,
    shouldApplyBicycleScaleFactor: Boolean = false
  ): (Double, Int, StreetMode) => Double = {
    val profileRequest = createProfileRequest
    (time: Double, linkId: Int, streetMode: StreetMode) =>
      {
        val edge = transportNetwork.streetLayer.edgeStore.getCursor(linkId)
        val maxSpeed: Double = vehicleType.maxVelocity.getOrElse(profileRequest.getSpeedForMode(streetMode))
        val minTravelTime = (edge.getLengthM / maxSpeed).ceil.toInt
        if (streetMode == StreetMode.CAR) {
          carWeightCalculator.calcTravelTime(linkId, travelTime, Some(vehicleType), time, shouldAddNoise)
        } else if (streetMode == StreetMode.BICYCLE && shouldApplyBicycleScaleFactor) {
          val scaleFactor = bikeLanesAdjustment.scaleFactor(vehicleType, linkId)
          minTravelTime * scaleFactor
        } else {
          minTravelTime
        }
      }
  }

  private val turnCostCalculator: TurnCostCalculator =
    new TurnCostCalculator(transportNetwork.streetLayer, true) {
      override def computeTurnCost(fromEdge: Int, toEdge: Int, streetMode: StreetMode): Int = 0
    }

  private def travelCostCalculator(timeValueOfMoney: Double, startTime: Int): TravelCostCalculator =
    (edge: EdgeStore#Edge, legDurationSeconds: Int, traversalTimeSeconds: Float) => {
      traversalTimeSeconds + (timeValueOfMoney * tollCalculator.calcTollByLinkId(
        edge.getEdgeIndex,
        startTime + legDurationSeconds
      )).toFloat
    }
}
