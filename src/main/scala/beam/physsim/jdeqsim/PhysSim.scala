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
import beam.router.Modes.BeamMode.CAR
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.config.BeamConfig
import beam.sim.population.AttributesOfIndividual
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.utils.csv.CsvWriter
import beam.utils.{DebugLib, ProfilingUtils, Statistics}
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import org.matsim.api.core.v01.events.{Event, PersonArrivalEvent, PersonDepartureEvent}
import org.matsim.api.core.v01.population.{Leg, Person, Population}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.algorithms.EventWriter
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Random, Try}

private case class ElementIndexToLeg(index: Int, leg: Leg)
private case class ElementIndexToRoutingResponse(index: Int, routingResponse: Try[RoutingResponse])
private case class RerouteStats(nRoutes: Int, totalRouteLen: Double, totalLinkCount: Int)

class CarTravelTimeHandler(isCACCVehicle: scala.collection.Map[String, Boolean])
    extends BasicEventHandler
    with StrictLogging {
  case class ArrivalDepartureEvent(personId: String, time: Int, `type`: String)

  private val events = new ArrayBuffer[ArrivalDepartureEvent]

  def shouldTakeThisEvent(personId: Id[Person], legMode: String): Boolean = {
    val pid = personId.toString.toLowerCase
    val isCacc = isCACCVehicle.getOrElse(pid, false)
    // No buses && no ridehailes and no cacc vehicles
    val isInvalid = pid.contains(":") || pid.contains("ridehailvehicle") || isCacc
    !isInvalid
  }

  override def handleEvent(event: Event): Unit = {
    event match {
      case pae: PersonArrivalEvent =>
        if (shouldTakeThisEvent(pae.getPersonId, pae.getLegMode)) {
          events += ArrivalDepartureEvent(pae.getPersonId.toString, pae.getTime.toInt, "arrival")
        }
      case pde: PersonDepartureEvent =>
        if (shouldTakeThisEvent(pde.getPersonId, pde.getLegMode)) {
          events += ArrivalDepartureEvent(pde.getPersonId.toString, pde.getTime.toInt, "departure")
        }
      case _ =>
    }
  }

  def compute: Statistics = {
    val groupedByPerson = events.groupBy(x => x.personId)
    val allTravelTimes = groupedByPerson.flatMap {
      case (personId, xs) =>
        val sorted = xs.sortBy(z => z.time)
        val sliding = sorted.sliding(2, 2)
        val travelTimes = sliding
          .map { curr =>
            if (curr.size != 2) {
              0
            } else {
              val travelTime = (curr(1).time - curr(0).time)
              travelTime
            }
          }
          .filter(x => x != 0)
        travelTimes
    }
    Statistics(allTravelTimes.map(t => t.toDouble / 60).toArray)
  }
}

class EventTypeCounter extends BasicEventHandler with StrictLogging {
  private val typeToNumberOfMessages = new mutable.HashMap[Class[_], Long]

  override def handleEvent(event: Event): Unit = {
    val clazz = event.getClass
    val prevValue = typeToNumberOfMessages.getOrElse(clazz, 0L)
    val newValue = prevValue + 1
    typeToNumberOfMessages.update(clazz, newValue)
  }

  def getStats: Seq[(String, Long)] = {
    typeToNumberOfMessages.map { case (clazz, cnt) => clazz.getSimpleName -> cnt }.toList.sortBy {
      case (clazz, _) => clazz
    }
  }
}

case class SimulationResult(
  iteration: Int,
  travelTime: TravelTime,
  eventTypeToNumberOfMessages: Seq[(String, Long)],
  carTravelTimeStats: Statistics
)

class PhysSim(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controlerIO: OutputDirectoryHierarchy,
  isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  iterationNumber: Int,
  shouldWritePhysSimEvents: Boolean,
  javaRnd: java.util.Random
) extends StrictLogging {

  val rnd: Random = new Random(javaRnd)
  val shouldLogWhenLinksAreNotTheSame: Boolean = false

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

  def run(nIterations: Int, reroutePerIterPct: Double, travelTime: TravelTime): TravelTime = {
    assert(nIterations >= 1)
    val carTravelTimeWriter: CsvWriter = {
      val fileName = controlerIO.getIterationFilename(iterationNumber, "MultiJDEQSim_car_travel_time.csv")
      new CsvWriter(fileName, Array("iteration", "avg", "median", "p75", "p95", "p99", "min", "max"))
    }
    val reroutedTravelTimeWriter: CsvWriter = {
      val fileName = controlerIO.getIterationFilename(iterationNumber, "MultiJDEQSim_rerouted_car_travel_time.csv")
      new CsvWriter(fileName, Array("iteration", "avg", "median", "p75", "p95", "p99", "min", "max"))
    }
    try {
      logger.info(s"Running PhysSim with nIterations = $nIterations and reroutePerIterPct = $reroutePerIterPct")
      run(
        1,
        nIterations,
        reroutePerIterPct,
        SimulationResult(-1, travelTime, Seq.empty, Statistics(Seq.empty)),
        SimulationResult(-1, travelTime, Seq.empty, Statistics(Seq.empty)),
        carTravelTimeWriter,
        reroutedTravelTimeWriter
      ).travelTime
    } finally {
      Try(carTravelTimeWriter.close())
      Try(reroutedTravelTimeWriter.close())
    }
  }

  @tailrec
  final def run(
    currentIter: Int,
    nIterations: Int,
    reroutePerIterPct: Double,
    firstResult: SimulationResult,
    lastResult: SimulationResult,
    carTravelTimeWriter: CsvWriter,
    reroutedTravelTimeWriter: CsvWriter,
  ): SimulationResult = {
    if (currentIter > nIterations) {
      logger.info("Last iteration compared with first")
      printStats(firstResult, lastResult)
      lastResult
    } else {
      val simulationResult = simulate(currentIter, shouldWritePhysSimEvents && currentIter == nIterations)
      carTravelTimeWriter.writeRow(
        Vector(
          currentIter,
          simulationResult.carTravelTimeStats.avg,
          simulationResult.carTravelTimeStats.median,
          simulationResult.carTravelTimeStats.p75,
          simulationResult.carTravelTimeStats.p95,
          simulationResult.carTravelTimeStats.p99,
          simulationResult.carTravelTimeStats.minValue,
          simulationResult.carTravelTimeStats.maxValue
        )
      )
      carTravelTimeWriter.flush()
      if (reroutePerIterPct > 0) {
        val before = printRouteStats(s"Before rerouting at $currentIter iter", population)
//        logger.info("AverageCarTravelTime before replanning")
//        PhysSim.printAverageCarTravelTime(getCarPeople(population))
        val reroutedTravelTimeStats = reroute(simulationResult.travelTime, reroutePerIterPct)
        reroutedTravelTimeWriter.writeRow(
          Vector(
            currentIter,
            reroutedTravelTimeStats.avg,
            reroutedTravelTimeStats.median,
            reroutedTravelTimeStats.p75,
            reroutedTravelTimeStats.p95,
            reroutedTravelTimeStats.p99,
            reroutedTravelTimeStats.minValue,
            reroutedTravelTimeStats.maxValue
          )
        )
        reroutedTravelTimeWriter.flush()
//        logger.info("AverageCarTravelTime after replanning")
//        PhysSim.printAverageCarTravelTime(getCarPeople(population))
        val after = printRouteStats(s"After rerouting at $currentIter iter", population)
        val absTotalLenDiff = Math.abs(before.totalRouteLen - after.totalRouteLen)
        val absAvgLenDiff = Math.abs(before.totalRouteLen / before.nRoutes - after.totalRouteLen / after.nRoutes)
        val absTotalCountDiff = Math.abs(before.totalLinkCount - after.totalLinkCount)
        val absAvgCountDiff = Math.abs(before.totalLinkCount / before.nRoutes - after.totalLinkCount / after.nRoutes)
        logger.info(s"""
             |Abs diff in total len: $absTotalLenDiff
             |Abs avg diff in len: $absAvgLenDiff
             |Abs dif in total link count: $absTotalCountDiff
             |Abs avg diff in link count: $absAvgCountDiff""".stripMargin)
      }
      printStats(lastResult, simulationResult)
      val realFirstResult = if (currentIter == 1) simulationResult else firstResult
      run(
        currentIter + 1,
        nIterations,
        reroutePerIterPct,
        realFirstResult,
        simulationResult,
        carTravelTimeWriter,
        reroutedTravelTimeWriter
      )
    }
  }

  private def printStats(prevResult: SimulationResult, currentResult: SimulationResult): Unit = {
    logger.info(
      s"eventTypeToNumberOfMessages at iteration ${prevResult.iteration}: \n${prevResult.eventTypeToNumberOfMessages.mkString("\n")}"
    )
    logger.info(
      s"eventTypeToNumberOfMessages at iteration ${currentResult.iteration}: \n${currentResult.eventTypeToNumberOfMessages
        .mkString("\n")}"
    )
    val diff =
      (currentResult.eventTypeToNumberOfMessages.map(_._1) ++ prevResult.eventTypeToNumberOfMessages.map(_._1)).toSet
    val diffMap = diff
      .foldLeft(Map.empty[String, Long]) {
        case (acc, key) =>
          val currVal = currentResult.eventTypeToNumberOfMessages.toMap.getOrElse(key, 0L)
          val prevVal = prevResult.eventTypeToNumberOfMessages.toMap.getOrElse(key, 0L)
          val absDiff = Math.abs(currVal - prevVal)
          acc + (key -> absDiff)
      }
      .toList
      .sortBy { case (k, _) => k }
    logger.info(s"Diff in eventTypeToNumberOfMessages map: \n${diffMap.mkString("\n")}")
//    PhysSim.printAverageCarTravelTime(getCarPeople)
    logger.info(s"Car travel time stats at iteration ${prevResult.iteration}: ${prevResult.carTravelTimeStats}")
    logger.info(s"Car travel time stats at iteration ${currentResult.iteration}: ${currentResult.carTravelTimeStats}")
  }

  private def getCarPeople(population: Population): Vector[Person] = {
    val carPeople = population.getPersons.values.asScala
      .filter(p => !p.getId.toString.contains("bus") && !p.getId.toString.contains(":"))
      .toVector
      .sortBy(x => x.getId.toString)
    carPeople
  }

  private def simulate(currentIter: Int, writeEvents: Boolean): SimulationResult = {
    val jdeqSimScenario = initScenario
    val jdeqsimEvents = new EventsManagerImpl
    val travelTimeCalculator =
      new TravelTimeCalculator(agentSimScenario.getNetwork, agentSimScenario.getConfig.travelTimeCalculator)

    val eventTypeCounter = new EventTypeCounter
    jdeqsimEvents.addHandler(eventTypeCounter)
    val carTravelTimeHandler = new CarTravelTimeHandler(isCACCVehicle.asScala.map {
      case (k, v) => k -> Boolean2boolean(v)
    })
    jdeqsimEvents.addHandler(carTravelTimeHandler)

    jdeqsimEvents.addHandler(travelTimeCalculator)
    jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam.debug.debugEnabled))
    val maybeEventWriter = if (writeEvents) {
      Some(addPhysSimEventsWriter(jdeqsimEvents))
    } else None

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
      ProfilingUtils.timed(s"JDEQSim iteration $currentIter", x => logger.info(x)) {
        val jdeqSimulation = getJDEQSimulation(jdeqSimScenario, jdeqsimEvents, maybeRoadCapacityAdjustmentFunction)
        logger.info(s"JDEQSim iteration $currentIter start");
        if (beamConfig.beam.debug.debugEnabled) {
          logger.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "));
        }
        jdeqSimulation.run()
        logger.info(s"JDEQSim iteration $currentIter finished");
      }

    } finally {
      maybeEventWriter.foreach(eventWriter => Try(eventWriter.closeFile()))
      maybeRoadCapacityAdjustmentFunction.foreach(_.reset())
    }
    jdeqsimEvents.finishProcessing()
    SimulationResult(
      iteration = currentIter,
      travelTime = travelTimeCalculator.getLinkTravelTimes,
      eventTypeToNumberOfMessages = eventTypeCounter.getStats,
      carTravelTimeStats = carTravelTimeHandler.compute
    )
  }

  private def reroute(travelTime: TravelTime, reroutePerIterPct: Double): Statistics = {
    val rightPeopleToReplan = getCarPeople(population)
    // logger.info(s"rightPeopleToReplan: ${rightPeopleToReplan.mkString(" ")}")
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
      val toReroute = rnd.shuffle(personToRoutes).take(takeN).toArray
      val r5Wrapper = new R5Wrapper(workerParams, travelTime, travelTimeError = 0)
      // Get new routes
      val result = ProfilingUtils.timed(
        s"Get new routes for ${takeN} out of ${rightPeopleToReplan.size} people which is ${100 * reroutePerIterPct}% of population",
        x => logger.info(x)
      ) {
        // TODO: `toReroute.par` => so it will run rerouting in parallel
        toReroute.par.map {
          case (person, xs) =>
            reroute(r5Wrapper, person, xs)
        }.seq
      }
      var newTravelTimes = new ArrayBuffer[Double]()
      ProfilingUtils.timed(s"Update routes for $takeN people", x => logger.info(x)) {
        var oldTravelTimes = new ArrayBuffer[Double]()
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
                        resp.itineraries.headOption.flatMap(_.legs.headOption.map(_.beamLeg)) match {
                          case Some(beamLeg) =>
                            oldTravelTimes += leg.getAttributes.getAttribute("travel_time").toString.toLong.toDouble
                            newTravelTimes += beamLeg.duration.toDouble

                            val javaLinkIds = beamLeg.travelPath.linkIds
                              .map(beamServices.networkHelper.getLinkUnsafe)
                              .map(_.getId)
                              .asJava
                            val newRoute = RouteUtils.createNetworkRoute(javaLinkIds, agentSimScenario.getNetwork)
                            leg.setRoute(newRoute)
                            leg.setDepartureTime(beamLeg.startTime)
                            leg.setTravelTime(0)
                            leg.getAttributes.putAttribute("travel_time", beamLeg.duration);
                            leg.getAttributes.putAttribute("departure_time", beamLeg.startTime);
                          case _ =>
                        }
                      }
                    )
                  case other => throw new IllegalStateException(s"Did not expect to see type ${other.getClass}: $other")
                }
            }
        }
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

  private def getR5UtmCoord(linkId: Int): Coord = {
    val r5EdgeCoord = beamServices.geo.coordOfR5Edge(beamServices.beamScenario.transportNetwork.streetLayer, linkId)
    beamServices.geo.wgs2Utm(r5EdgeCoord)
  }

  private def printRouteStats(str: String, population: Population): RerouteStats = {
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
      // route.getLinkIds does not contain start and end links, so that's why 2 +
      2 + route.getLinkIds.size()
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

  private def verifyResponse(
    routingRequest: RoutingRequest,
    leg: Leg,
    maybeRoutingResponse: Try[RoutingResponse]
  ): Unit = {
    maybeRoutingResponse.fold(
      _ => (),
      resp => {
        val r5Leg = resp.itineraries.head.legs.head
        val startLinkId = r5Leg.beamLeg.travelPath.linkIds.head
        val endLinkId = r5Leg.beamLeg.travelPath.linkIds.last
        val matsimStartLinkId = leg.getRoute.getStartLinkId.toString.toInt
        val matsimEndLinkId = leg.getRoute.getEndLinkId.toString.toInt
        if (startLinkId != matsimStartLinkId && shouldLogWhenLinksAreNotTheSame) {
          logger.info(s"""startLinkId[$startLinkId] != matsimStartLinkId[$matsimStartLinkId].
             |r5Leg: $r5Leg. LinkIds=[${r5Leg.beamLeg.travelPath.linkIds.mkString(", ")}]
             |MATSim leg: ${leg}""".stripMargin)
        }
        if (endLinkId != matsimEndLinkId && shouldLogWhenLinksAreNotTheSame) {
          logger.info(s"""endLinkId[$endLinkId] != matsimEndLinkId[$matsimEndLinkId].
             |r5Leg: $r5Leg. LinkIds=[${r5Leg.beamLeg.travelPath.linkIds.mkString(", ")}]
             |MATSim leg: ${leg}""".stripMargin)
        }
        // r5Leg
        ()
      }
    )
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
        verifyResponse(routingRequest, leg, maybeRoutingResponse)
        ElementIndexToRoutingResponse(idx, maybeRoutingResponse)
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

  private def addPhysSimEventsWriter(eventsManager: EventsManager): EventWriter = {
    val eventsSampling = beamConfig.beam.physsim.eventsSampling
    val eventsForFullVersionOfVia = beamConfig.beam.physsim.eventsForFullVersionOfVia
    val fileName = controlerIO.getIterationFilename(iterationNumber, "physSimEvents.xml.gz")
    val eventsWriterXML = new EventWriterXML_viaCompatible(fileName, eventsForFullVersionOfVia, eventsSampling)
    eventsManager.addHandler(eventsWriterXML)
    eventsWriterXML
  }
}

object PhysSim extends LazyLogging {

  def printAverageCarTravelTime(people: Seq[Person]): Unit = {
    val timeToTravelTime = people.flatMap { person =>
      person.getSelectedPlan.getPlanElements.asScala.collect {
        case leg: Leg =>
          val travelTime = leg.getAttributes.getAttribute("travel_time").toString.toDouble.toInt
          travelTime
      }
    }
    logger.info(s"Some others stats about travel time: ${Statistics(timeToTravelTime.map(_.toDouble))}")
  }
}
