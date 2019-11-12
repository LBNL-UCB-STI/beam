package beam.physsim.jdeqsim

import beam.analysis.via.EventWriterXML_viaCompatible
import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.{Hao2018CaccRoadCapacityAdjustmentFunction, RoadCapacityAdjustmentFunction}
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation
import beam.router.FreeFlowTravelTime
import beam.router.gtfs.FareCalculator
import beam.router.osm.TollCalculator
import beam.router.r5.{R5Wrapper, WorkerParameters}
import beam.sim.{BeamConfigChangesObservable, BeamScenario, BeamServices}
import beam.sim.config.BeamConfig
import beam.utils.{DebugLib, NetworkHelper, ProfilingUtils, RandomUtils}
import com.conveyal.r5.transit.TransportNetwork
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population.{Activity, Leg, Person, Plan, Population, Route}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.mobsim.jdeqsim.{JDEQSimConfigGroup, Message, Road}
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.vehicles.Vehicles

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.Random

class PhysSim(beamConfig: BeamConfig, agentSimScenario: Scenario, population: Population,
              beamServices: BeamServices,
              controlerIO: OutputDirectoryHierarchy,
              isCACCVehicle: java.util.Map[String, java.lang.Boolean],
              beamConfigChangesObservable: BeamConfigChangesObservable,
              iterationNumber: Int,
              shouldWritePhysSimEvents: Boolean) extends StrictLogging {

  val workerParams: WorkerParameters = WorkerParameters(beamConfig = beamConfig, transportNetwork = beamServices.beamScenario.transportNetwork,
    vehicleTypes = beamServices.beamScenario.vehicleTypes, fuelTypePrices = beamServices.beamScenario.fuelTypePrices, ptFares = beamServices.beamScenario.ptFares,
    geo = beamServices.geo, dates = beamServices.beamScenario.dates, networkHelper = beamServices.networkHelper, fareCalculator = beamServices.fareCalculator,
    tollCalculator = beamServices.tollCalculator)

  def run(nIterations: Int, reroutePerIterPct: Double): TravelTime = {
    assert(nIterations >= 1)

    run(1, nIterations, reroutePerIterPct, new FreeFlowTravelTime)
  }

  @tailrec
  final def run(currentIter: Int, nIterations: Int, reroutePerIterPct: Double, lastTravelTime: TravelTime): TravelTime = {
    if (currentIter > nIterations) lastTravelTime
    else {
      val travelTime = simulate(shouldWritePhysSimEvents && currentIter == nIterations - 1)
      reroute(travelTime, reroutePerIterPct)
      run(currentIter + 1, nIterations, reroutePerIterPct, travelTime)
    }
  }


  private def simulate(writeEvents: Boolean): TravelTime = {
    val jdeqSimScenario = initScenario
    val jdeqsimEvents = new EventsManagerImpl
    val travelTimeCalculator = new TravelTimeCalculator(agentSimScenario.getNetwork, agentSimScenario.getConfig.travelTimeCalculator)
    jdeqsimEvents.addHandler(travelTimeCalculator)
    jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam.debug.debugEnabled))
    if (writeEvents) {
      addPhysSimEventsWriter(jdeqsimEvents)
    }

    val maybeRoadCapacityAdjustmentFunction = if (beamConfig.beam.physsim.jdeqsim.cacc.enabled) {
      Some(new Hao2018CaccRoadCapacityAdjustmentFunction(
        beamConfig,
        iterationNumber,
        controlerIO,
        beamConfigChangesObservable
      ))
    }
    else None

    try {
      val jdeqSimulation = getJDEQSimulation(jdeqSimScenario, jdeqsimEvents, maybeRoadCapacityAdjustmentFunction)
      logger.info("JDEQSim Start");
      if (beamConfig.beam.debug.debugEnabled) {
        logger.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "));
      }
      jdeqSimulation.run()
      logger.info("JDEQSim Start");

      travelTimeCalculator.getLinkTravelTimes
    }
    finally {
      maybeRoadCapacityAdjustmentFunction.foreach(_.reset())
    }
  }

  private def reroute(travelTime: TravelTime, reroutePerIterPct: Double): Unit = {
    val personToRoutes = population.getPersons.values.asScala.toVector.flatMap(_.getPlans.asScala.toVector).map { plan =>
      val route = plan.getPlanElements.asScala.zipWithIndex.collect {
        case (leg: Leg, idx: Int) if leg.getMode.equalsIgnoreCase("car") =>
          idx -> leg.getRoute
      }.toVector
      plan.getPerson -> route
    }
    val pctToNumberPersonToTake = (personToRoutes.size * reroutePerIterPct).toInt
    val takeN = if (pctToNumberPersonToTake > personToRoutes.size) personToRoutes.size else pctToNumberPersonToTake
    if (takeN > 0) {
      val toReroute = new Random(beamConfig.matsim.modules.global.randomSeed).shuffle(personToRoutes).take(takeN)
      toReroute.map { case (person, xs: Vector[(Int, Route)]) =>
        reroute(travelTime, person, xs)
      }
      println(toReroute)
    }
  }

  private def reroute(travelTime: TravelTime, person: Person, elemIdxToRoute: Vector[(Int, Route)]): Unit = {
    val rr = new R5Wrapper(workerParams, travelTime)
    rr.calcRoute()
  }

  def getJDEQSimulation(jdeqSimScenario: MutableScenario, jdeqsimEvents: EventsManager,
                        maybeRoadCapacityAdjustmentFunction: Option[RoadCapacityAdjustmentFunction]): org.matsim.core.mobsim.jdeqsim.JDEQSimulation = {
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
        logger.info("caccCategoryRoadCount: " + caccCategoryRoadCount + " out of " + jdeqSimScenario.getNetwork.getLinks.values.size)
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