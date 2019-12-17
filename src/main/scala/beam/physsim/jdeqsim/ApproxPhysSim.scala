package beam.physsim.jdeqsim

import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleCategory}
import beam.agentsim.events.SpaceTime
import beam.agentsim.events.handling.{BeamEventsLogger, BeamEventsWriterCSV}
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
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.algorithms.EventWriter
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.routes.{NetworkRoute, RouteUtils}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.utils.objectattributes.attributable.AttributesUtils

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Random, Try}

class ApproxPhysSim(
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
  val finalPopulation: Population = initPopulation(population)

  val peopleWhichCanBeTaken: mutable.Set[Person] = {
    val buses = getBuses(population).toSet
    val canTake = population.getPersons.values().asScala.filter(p => !buses.contains(p))
    mutable.HashSet[Person](canTake.toSeq: _*)
  }

  val numberOfPeopleToSimulateEveryIter: Array[Int] = {
    val percentToSimulate: Array[Double] = Array(10, 10, 10, 10, 10, 10, 10, 10, 10, 10).map(_.toDouble / 100)
    val xs = percentToSimulate.map(p => (peopleWhichCanBeTaken.size * p).toInt)
    if (xs.sum != peopleWhichCanBeTaken.size) {
      val leftDueToRounding = peopleWhichCanBeTaken.size - xs.sum
      logger.info(s"leftDueToRounding: $leftDueToRounding")
      xs(xs.length - 1) = xs(xs.length - 1) + leftDueToRounding
    }
    xs
  }

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

  def run(travelTime: TravelTime): TravelTime = {
    val carTravelTimeWriter: CsvWriter = {
      val fileName = controlerIO.getIterationFilename(iterationNumber, "MultiJDEQSim_car_travel_time.csv")
      new CsvWriter(fileName, Array("iteration", "avg", "median", "p75", "p95", "p99", "min", "max"))
    }
    val reroutedTravelTimeWriter: CsvWriter = {
      val fileName = controlerIO.getIterationFilename(iterationNumber, "MultiJDEQSim_rerouted_car_travel_time.csv")
      new CsvWriter(fileName, Array("iteration", "avg", "median", "p75", "p95", "p99", "min", "max"))
    }
    try {
      logger.info(s"Running ApproxPhysSim")
      run(
        1,
        numberOfPeopleToSimulateEveryIter.length,
        numberOfPeopleToSimulateEveryIter.head,
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
    numberOfPeopleToTake: Int,
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
      logger.info(s"finalPopulation size before: ${finalPopulation.getPersons.size}")
      // Get next set of people
      val nextSetOfPeople = {
        val setOfPeople = getNextSetOfPeople(peopleWhichCanBeTaken, rnd, numberOfPeopleToTake)
        logger.info(s"setOfPeople size is ${setOfPeople.size}. numberOfPeopleToTake: $numberOfPeopleToTake")
        // Remove them from original set
        setOfPeople.foreach(peopleWhichCanBeTaken.remove)
        val asCopy = setOfPeople.map { person =>
          createCopyOfPerson(person, finalPopulation.getFactory)
        }
        asCopy.foreach(finalPopulation.addPerson)
        asCopy
      }
      logger.info(
        s"finalPopulation size after: ${finalPopulation.getPersons.size}. Original population size: ${population.getPersons.size}"
      )

      val simulationResult = simulate(currentIter, writeEvents = shouldWritePhysSimEvents && currentIter == nIterations)
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
      val before = printRouteStats(s"Before rerouting at $currentIter iter", finalPopulation)
      //        logger.info("AverageCarTravelTime before replanning")
      //        PhysSim.printAverageCarTravelTime(getCarPeople(population))
      val reroutedTravelTimeStats = reroutePeople(simulationResult.travelTime, nextSetOfPeople.toVector)
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
      val after = printRouteStats(s"After rerouting at $currentIter iter", finalPopulation)
      val absTotalLenDiff = Math.abs(before.totalRouteLen - after.totalRouteLen)
      val absAvgLenDiff = Math.abs(before.totalRouteLen / before.nRoutes - after.totalRouteLen / after.nRoutes)
      val absTotalCountDiff = Math.abs(before.totalLinkCount - after.totalLinkCount)
      val absAvgCountDiff = Math.abs(before.totalLinkCount / before.nRoutes - after.totalLinkCount / after.nRoutes)
      logger.info(s"""
                     |Abs diff in total len: $absTotalLenDiff
                     |Abs avg diff in len: $absAvgLenDiff
                     |Abs dif in total link count: $absTotalCountDiff
                     |Abs avg diff in link count: $absAvgCountDiff""".stripMargin)
      printStats(lastResult, simulationResult)
      val realFirstResult = if (currentIter == 1) simulationResult else firstResult
      val nextNumberOfPeopleToTake = numberOfPeopleToSimulateEveryIter.lift(currentIter).getOrElse(-1)
      run(
        currentIter + 1,
        nIterations,
        nextNumberOfPeopleToTake,
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

  private def simulate(currentPhysSimIter: Int, writeEvents: Boolean): SimulationResult = {
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
      val writer = PhysSimEventWriter(beamServices, jdeqsimEvents)
      jdeqsimEvents.addHandler(writer)
      Some(writer)
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
      ProfilingUtils.timed(
        s"JDEQSim iteration $currentPhysSimIter for ${finalPopulation.getPersons.size()} people",
        x => logger.info(x)
      ) {
        val jdeqSimulation = getJDEQSimulation(jdeqSimScenario, jdeqsimEvents, maybeRoadCapacityAdjustmentFunction)
        logger.info(s"JDEQSim iteration $currentPhysSimIter start");
        if (beamConfig.beam.debug.debugEnabled) {
          logger.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "));
        }
        jdeqSimulation.run()
        logger.info(s"JDEQSim iteration $currentPhysSimIter finished");
      }

    } finally {
      maybeEventWriter.foreach { wrt =>
        Try(wrt.closeFile())
      }
      maybeRoadCapacityAdjustmentFunction.foreach(_.reset())
    }
    jdeqsimEvents.finishProcessing()
    SimulationResult(
      iteration = currentPhysSimIter,
      travelTime = travelTimeCalculator.getLinkTravelTimes,
      eventTypeToNumberOfMessages = eventTypeCounter.getStats,
      carTravelTimeStats = carTravelTimeHandler.compute
    )
  }

  private def getNextSetOfPeople(
    xs: collection.Set[Person],
    rnd: Random,
    numberOfPeopleToTake: Int
  ): collection.Set[Person] = {
    val takeN = if (numberOfPeopleToTake > xs.size) xs.size else numberOfPeopleToTake
    val next = rnd.shuffle(xs).take(takeN)
    next
  }

  private def reroute(travelTime: TravelTime, reroutePerIterPct: Double): Statistics = {
    val rightPeopleToReplan = getCarPeople(finalPopulation)
    // logger.info(s"rightPeopleToReplan: ${rightPeopleToReplan.mkString(" ")}")

    val peopleWithCarLegs = rightPeopleToReplan.filter { person =>
      assert(person.getPlans.size() == 1)
      val hasCarLeg = person.getSelectedPlan.getPlanElements.asScala.exists {
        case activity: Activity => false
        case leg: Leg           => leg.getMode.equalsIgnoreCase("car")
      }
      hasCarLeg
    }

    val pctToNumberPersonToTake = (peopleWithCarLegs.size * reroutePerIterPct).toInt
    val takeN =
      if (pctToNumberPersonToTake > peopleWithCarLegs.size) peopleWithCarLegs.size else pctToNumberPersonToTake
    val sampledPeople = rnd.shuffle(peopleWithCarLegs).take(takeN)

    reroutePeople(travelTime, sampledPeople)
  }

  private def reroutePeople(travelTime: TravelTime, toReroute: Vector[Person]): Statistics = {
    if (toReroute.nonEmpty) {
      val personToRoutes = toReroute.flatMap(_.getPlans.asScala.toVector).map { plan =>
        val route = plan.getPlanElements.asScala.zipWithIndex.collect {
          case (leg: Leg, idx: Int) if leg.getMode.equalsIgnoreCase("car") =>
            ElementIndexToLeg(idx, leg)
        }.toVector
        plan.getPerson -> route
      }

      val r5Wrapper = new R5Wrapper(workerParams, travelTime, travelTimeError = 0)
      // Get new routes
      val result = ProfilingUtils.timed(s"Get new routes for ${toReroute.size} people", x => logger.info(x)) {
        // TODO: `sampledPeople.par` => so it will run rerouting in parallel
        personToRoutes.par.map {
          case (person, xs) =>
            reroute(r5Wrapper, person, xs)
        }.seq
      }
      var newTravelTimes = new ArrayBuffer[Double]()
      ProfilingUtils.timed(s"Update routes for ${toReroute.size} people", x => logger.info(x)) {
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
        val minimumRoadSpeedInMetersPerSecond = beamConfig.beam.physsim.jdeqsim.cacc.minimumRoadSpeedInMetersPerSecond
        new JDEQSimulation(
          config,
          jdeqSimScenario,
          jdeqsimEvents,
          caccSettings,
          speedAdjustmentFactor,
          minimumRoadSpeedInMetersPerSecond
        )

      case None =>
        logger.info("CACC disabled")
        new org.matsim.core.mobsim.jdeqsim.JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents)
    }
  }

  private def initScenario = {
    val jdeqSimScenario = ScenarioUtils.createScenario(agentSimScenario.getConfig).asInstanceOf[MutableScenario]
    jdeqSimScenario.setNetwork(agentSimScenario.getNetwork)
    jdeqSimScenario.setPopulation(finalPopulation)
    jdeqSimScenario
  }

  private def createViaXmlEventsWriter(currentPhysSimIter: Int): EventWriter = {
    val eventsSampling = beamConfig.beam.physsim.eventsSampling
    val eventsForFullVersionOfVia = beamConfig.beam.physsim.eventsForFullVersionOfVia
    val fileName =
      controlerIO.getIterationFilename(iterationNumber, s"${currentPhysSimIter}_MultiJDEQSim_physSimEvents.xml.gz")
    val eventsWriterXML = new EventWriterXML_viaCompatible(fileName, eventsForFullVersionOfVia, eventsSampling)
    eventsWriterXML
  }

  private def createCsvWriter(currentPhysSimIter: Int, jdeqsimEvents: EventsManagerImpl): BeamEventsWriterCSV = {
    val fileName =
      controlerIO.getIterationFilename(iterationNumber, s"${currentPhysSimIter}_MultiJDEQSim_physSimEvents.csv.gz")
    val beamEventLogger = new BeamEventsLogger(
      beamServices,
      beamServices.matsimServices,
      jdeqsimEvents,
      beamServices.beamConfig.beam.physsim.events.eventsToWrite
    )
    val csvEventsWriter = new BeamEventsWriterCSV(fileName, beamEventLogger, beamServices, null)
    csvEventsWriter
  }

  def getBuses(population: Population): Iterable[Person] = {
    population.getPersons.values().asScala.filter(p => p.getId.toString.contains(":"))
  }

  def initPopulation(population: Population): Population = {
    val buses = getBuses(population)
    val newPop = PopulationUtils.createPopulation(agentSimScenario.getConfig)
    buses.foreach { bus =>
      val person: Person = createCopyOfPerson(bus, newPop.getFactory)
      newPop.addPerson(person)
    }
    newPop
  }

  private def createCopyOfPerson(srcPerson: Person, factory: PopulationFactory) = {
    val person = factory.createPerson(srcPerson.getId)
    val plan = factory.createPlan
    PopulationUtils.copyFromTo(srcPerson.getSelectedPlan, plan)
    person.addPlan(plan)
    person.setSelectedPlan(plan)
    AttributesUtils.copyAttributesFromTo(srcPerson, person)
    person
  }
}

object ApproxPhysSim extends LazyLogging {

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
