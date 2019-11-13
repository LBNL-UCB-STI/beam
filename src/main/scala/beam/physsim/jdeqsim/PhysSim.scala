package beam.physsim.jdeqsim

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.analysis.via.EventWriterXML_viaCompatible
import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.{
  Hao2018CaccRoadCapacityAdjustmentFunction,
  RoadCapacityAdjustmentFunction
}
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation
import beam.router.BeamRouter.{Access, RoutingRequest, RoutingResponse}
import beam.router.FreeFlowTravelTime
import beam.router.Modes.BeamMode.CAR
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.utils.{DebugLib, ProfilingUtils}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.population.{Leg, Person, Population}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.population.routes.RouteUtils
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{Random, Try}

private case class ElementIndexToLeg(index: Int, leg: Leg)
private case class ElementIndexToRoutingResponse(index: Int, routingResponse: Try[RoutingResponse])

class PhysSim(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controlerIO: OutputDirectoryHierarchy,
  isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  iterationNumber: Int,
  shouldWritePhysSimEvents: Boolean
) extends StrictLogging {

  val workerParams: WorkerParameters = WorkerParameters(
    beamConfig = beamConfig,
    transportNetwork = beamServices.beamScenario.transportNetwork,
    vehicleTypes = beamServices.beamScenario.vehicleTypes,
    fuelTypePrices = beamServices.beamScenario.fuelTypePrices,
    ptFares = beamServices.beamScenario.ptFares,
    geo = beamServices.geo,
    dates = beamServices.beamScenario.dates,
    networkHelper = beamServices.networkHelper,
    fareCalculator = beamServices.fareCalculator,
    tollCalculator = beamServices.tollCalculator
  )

  val bodyType: BeamVehicleType = beamServices.beamScenario.vehicleTypes(
    Id.create(beamServices.beamScenario.beamConfig.beam.agentsim.agents.bodyType, classOf[BeamVehicleType])
  )

  val (carVehId, carVehType) = beamServices.beamScenario.vehicleTypes
    .collect { case (k, v) if v.vehicleCategory == VehicleCategory.Car => (k, v) }
    .maxBy(_._2.sampleProbabilityWithinCategory)

  def run(nIterations: Int, reroutePerIterPct: Double): TravelTime = {
    assert(nIterations >= 1)
    logger.info(s"Running PhysSim with nIterations = $nIterations and reroutePerIterPct = $reroutePerIterPct")
    run(1, nIterations, reroutePerIterPct, new FreeFlowTravelTime)
  }

  @tailrec
  final def run(
    currentIter: Int,
    nIterations: Int,
    reroutePerIterPct: Double,
    lastTravelTime: TravelTime
  ): TravelTime = {
    if (currentIter > nIterations) lastTravelTime
    else {
      val travelTime = simulate(shouldWritePhysSimEvents && currentIter == nIterations - 1)
      if (reroutePerIterPct > 0) {
        reroute(travelTime, reroutePerIterPct)
      }
      run(currentIter + 1, nIterations, reroutePerIterPct, travelTime)
    }
  }

  private def simulate(writeEvents: Boolean): TravelTime = {
    val jdeqSimScenario = initScenario
    val jdeqsimEvents = new EventsManagerImpl
    val travelTimeCalculator =
      new TravelTimeCalculator(agentSimScenario.getNetwork, agentSimScenario.getConfig.travelTimeCalculator)
    jdeqsimEvents.addHandler(travelTimeCalculator)
    jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam.debug.debugEnabled))
    if (writeEvents) {
      addPhysSimEventsWriter(jdeqsimEvents)
    }

    val maybeRoadCapacityAdjustmentFunction = if (beamConfig.beam.physsim.jdeqsim.cacc.enabled) {
      Some(
        new Hao2018CaccRoadCapacityAdjustmentFunction(
          beamConfig,
          iterationNumber,
          controlerIO,
          beamConfigChangesObservable
        )
      )
    } else None

    try {
      val jdeqSimulation = getJDEQSimulation(jdeqSimScenario, jdeqsimEvents, maybeRoadCapacityAdjustmentFunction)
      logger.info("JDEQSim Start");
      if (beamConfig.beam.debug.debugEnabled) {
        logger.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "));
      }
      jdeqSimulation.run()
      logger.info("JDEQSim Finished");

      travelTimeCalculator.getLinkTravelTimes
    } finally {
      maybeRoadCapacityAdjustmentFunction.foreach(_.reset())
    }
  }

  private def reroute(travelTime: TravelTime, reroutePerIterPct: Double): Unit = {
    val rightPeopleToReplan =
      population.getPersons.values.asScala.filter(p => !p.getId.toString.contains("bus")).toVector
    val personToRoutes = rightPeopleToReplan.flatMap(_.getPlans.asScala.toVector).map { plan =>
      val route = plan.getPlanElements.asScala.zipWithIndex.collect {
        case (leg: Leg, idx: Int) if leg.getMode.equalsIgnoreCase("car") =>
          ElementIndexToLeg(idx, leg)
      }.toVector
      plan.getPerson -> route
    }
    val pctToNumberPersonToTake = (personToRoutes.size * reroutePerIterPct).toInt
    val takeN = if (pctToNumberPersonToTake > personToRoutes.size) personToRoutes.size else pctToNumberPersonToTake
    if (takeN > 0) {
      val toReroute =
        new Random(beamConfig.matsim.modules.global.randomSeed).shuffle(personToRoutes).take(takeN).toArray
      val r5Wrapper = new R5Wrapper(workerParams, travelTime, isZeroIter = iterationNumber == 0)
      // Get new routes
      val result = ProfilingUtils.timed(
        s"Get new routes for ${takeN} out of ${rightPeopleToReplan.size} people which is ${100 * reroutePerIterPct}% of population",
        x => logger.info(x)
      ) {
        // `toReroute.par` => so it will run rerouting in parallel
        toReroute.par.map {
          case (person, xs) =>
            reroute(r5Wrapper, person, xs)
        }.seq
      }
      ProfilingUtils.timed(s"Update routes for $takeN people", x => logger.info(x)) {
        // Update plans
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
                        val beamLeg = resp.itineraries.head.legs.head.beamLeg
                        val javaLinkIds =
                          beamLeg.travelPath.linkIds.map(beamServices.networkHelper.getLinkUnsafe).map(_.getId).asJava
                        val newRoute = RouteUtils.createNetworkRoute(javaLinkIds, agentSimScenario.getNetwork)
                        leg.setRoute(newRoute)
                      }
                    )
                  case other => throw new IllegalStateException(s"Did not expect to see type ${other.getClass}: $other")
                }
            }
        }
      }

    }
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
        val startCoord = beamServices.networkHelper.getLinkUnsafe(route.getStartLinkId.toString.toInt).getCoord
        val endCoord = beamServices.networkHelper.getLinkUnsafe(route.getEndLinkId.toString.toInt).getCoord
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
        ElementIndexToRoutingResponse(idx, Try(r5.calcRoute(routingRequest)))
    }
    person -> idxToResponse
  }

  def getJDEQSimulation(
    jdeqSimScenario: MutableScenario,
    jdeqsimEvents: EventsManager,
    maybeRoadCapacityAdjustmentFunction: Option[RoadCapacityAdjustmentFunction]
  ): org.matsim.core.mobsim.jdeqsim.JDEQSimulation = {
    val config = new JDEQSimConfigGroup
    val flowCapacityFactor = beamConfig.beam.physsim.flowCapacityFactor
    config.setFlowCapacityFactor(flowCapacityFactor)
    config.setStorageCapacityFactor(beamConfig.beam.physsim.storageCapacityFactor)
    config.setSimulationEndTime(beamConfig.matsim.modules.qsim.endTime)
    maybeRoadCapacityAdjustmentFunction match {
      case Some(roadCapacityAdjustmentFunction) =>
        logger.info("CACC enabled")
        var caccCategoryRoadCount = 0
        for (link <- jdeqSimScenario.getNetwork.getLinks.values.asScala) {
          if (roadCapacityAdjustmentFunction.isCACCCategoryRoad(link)) caccCategoryRoadCount += 1
        }
        logger.info(
          "caccCategoryRoadCount: " + caccCategoryRoadCount + " out of " + jdeqSimScenario.getNetwork.getLinks.values.size
        )
        val caccSettings = new CACCSettings(isCACCVehicle, roadCapacityAdjustmentFunction)
        val speedAdjustmentFactor = beamConfig.beam.physsim.jdeqsim.cacc.speedAdjustmentFactor
        new JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents, caccSettings, speedAdjustmentFactor)

      case None =>
        logger.info("CACC disabled")
        new org.matsim.core.mobsim.jdeqsim.JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents)
    }
  }

  private def initScenario = {
    val jdeqSimScenario = ScenarioUtils.createScenario(agentSimScenario.getConfig).asInstanceOf[MutableScenario]
    jdeqSimScenario.setNetwork(agentSimScenario.getNetwork)
    jdeqSimScenario.setPopulation(population)
    jdeqSimScenario
  }

  private def addPhysSimEventsWriter(eventsManager: EventsManager): Unit = {
    val eventsSampling = beamConfig.beam.physsim.eventsSampling
    val eventsForFullVersionOfVia = beamConfig.beam.physsim.eventsForFullVersionOfVia
    val fileName = controlerIO.getIterationFilename(iterationNumber, "physSimEvents.xml.gz")
    val eventsWriterXML = new EventWriterXML_viaCompatible(fileName, eventsForFullVersionOfVia, eventsSampling)
    eventsManager.addHandler(eventsWriterXML)
  }
}
