package beam.physsim.jdeqsim

import beam.analysis.physsim.{PhyssimCalcLinkStats, PhyssimSpeedHandler}
import beam.analysis.plot.PlotGraph
import beam.physsim.bprsim.{BPRSimConfig, BPRSimulation, ParallelBPRSimulation}
import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.physsim.jdeqsim.cacc.roadcapacityadjustmentfunctions.{
  Hao2018CaccRoadCapacityAdjustmentFunction,
  RoadCapacityAdjustmentFunction
}
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation
import beam.physsim.{PickUpDropOffCollector, PickUpDropOffHolder}
import beam.sim.config.BeamConfig
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.utils.ConcurrentUtils.parallelExecution
import beam.utils.NetworkEdgeOutputGenerator.beamConfig
import beam.utils.{DebugLib, ProfilingUtils}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.analysis.LegHistogram
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Population
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.events.{EventsManagerImpl, ParallelEventsManagerImpl}
import org.matsim.core.mobsim.framework.Mobsim
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.core.utils.misc.Time

import scala.concurrent.ExecutionContext
import scala.collection.JavaConverters._
import scala.util.Try

class JDEQSimRunner(
  val beamConfig: BeamConfig,
  val jdeqSimScenario: Scenario,
  val population: Population,
  val beamServices: BeamServices,
  val controlerIO: OutputDirectoryHierarchy,
  val isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  val beamConfigChangesObservable: BeamConfigChangesObservable,
  val agentSimIterationNumber: Int,
  val maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
) extends StrictLogging {

  import JDEQSimRunner._

  def simulate(currentPhysSimIter: Int, writeEvents: Boolean): SimulationResult = {
    val jdeqsimEvents = createEventManager
    val travelTimeCalculator =
      new TravelTimeCalculator(jdeqSimScenario.getNetwork, jdeqSimScenario.getConfig.travelTimeCalculator)
    val legHistogram = new LegHistogram(
      population,
      jdeqsimEvents,
      beamConfig.beam.outputs.stats.binSize,
      getNoOfBins(beamConfig.beam.outputs.stats.binSize)
    )

    val linkStatsGraph = new PhyssimCalcLinkStats(
      jdeqSimScenario.getNetwork,
      controlerIO,
      beamServices.beamConfig,
      jdeqSimScenario.getConfig.travelTimeCalculator,
      beamConfigChangesObservable
    )
    linkStatsGraph.notifyIterationStarts(jdeqsimEvents, jdeqSimScenario.getConfig.travelTimeCalculator)

    val eventToHourFrequency = new EventToHourFrequency(controlerIO)
    jdeqsimEvents.addHandler(eventToHourFrequency)

    val eventTypeCounter = new EventTypeCounter
    jdeqsimEvents.addHandler(eventTypeCounter)
    val carTravelTimeHandler = new CarTravelTimeHandler(isCACCVehicle.asScala.map { case (k, v) =>
      k -> Boolean2boolean(v)
    })
    jdeqsimEvents.addHandler(carTravelTimeHandler)

    jdeqsimEvents.addHandler(travelTimeCalculator)
    jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam.debug.debugEnabled))

    val physsimSpeedHandler = new PhyssimSpeedHandler(population, controlerIO, beamConfig)
    jdeqsimEvents.addHandler(physsimSpeedHandler)

    val maybeEventWriter = if (writeEvents) {
      val writer = PhysSimEventWriter(beamServices, jdeqsimEvents)
      //adding this writer as a BEAM shutdown listener so that it could prevent BEAM from exiting
      //before the writer writes everything to disk.
      beamServices.matsimServices.addControlerListener(writer)
      jdeqsimEvents.addHandler(writer)
      Some(writer)
    } else None

    val maybeCaccSettings = if (beamConfig.beam.physsim.jdeqsim.cacc.enabled) Some(createCaccSettings()) else None

    val maybePickUpDropOffHolder: Option[PickUpDropOffHolder] =
      if (
        beamConfig.beam.physsim.pickUpDropOffAnalysis.enabled
        && maybePickUpDropOffCollector.nonEmpty
      ) {
        Some(createPickUpDropOffHolder(maybePickUpDropOffCollector.get))
      } else {
        None
      }

    val simName = beamConfig.beam.physsim.name
    jdeqsimEvents.initProcessing()
    try {
      ProfilingUtils.timed(
        s"PhysSim iteration $currentPhysSimIter for ${population.getPersons.size()} people",
        x => logger.info(x)
      ) {
        val jdeqSimulation =
          getPhysSimulation(jdeqSimScenario, jdeqsimEvents, maybeCaccSettings, maybePickUpDropOffHolder, simName)
        logger.info(s"PhysSim iteration $currentPhysSimIter start")
        if (beamConfig.beam.debug.debugEnabled) {
          logger.info(DebugLib.getMemoryLogMessage("Memory Use Before PhysSim: "))
        }
        jdeqSimulation.run()
        logger.info(s"PhysSim iteration $currentPhysSimIter finished")
        maybePickUpDropOffHolder.foreach { holder =>
          logger.info(
            s"During PhysSim simulation by PickUpDropOffHolder ${holder.linkTravelTimeAnalyzed} link analyzed, ${holder.linkTravelTimeAffected} links travel time changed."
          )
        }
      }

    } finally {
      Try(jdeqsimEvents.finishProcessing())
      maybeEventWriter.foreach { wrt =>
        Try(wrt.closeFile())
      }
      maybeCaccSettings.foreach(_.roadCapacityAdjustmentFunction.reset())

      parallelExecution(
        legHistogram.getLegModes.forEach(mode => {
          new PlotGraph().writeGraphic(
            legHistogram,
            controlerIO,
            s"$currentPhysSimIter.physsimTripHistogram",
            "time (binSize=<?> sec)",
            mode,
            agentSimIterationNumber,
            beamConfig.beam.outputs.stats.binSize
          )
        }),
        linkStatsGraph.notifyIterationEnds(agentSimIterationNumber, travelTimeCalculator.getLinkTravelTimes),
        eventToHourFrequency.notifyIterationEnds(
          new IterationEndsEvent(beamServices.matsimServices, agentSimIterationNumber)
        ),
        physsimSpeedHandler.notifyIterationEnds(agentSimIterationNumber),
        ()
      )(scala.concurrent.ExecutionContext.global)
    }
    SimulationResult(
      iteration = currentPhysSimIter,
      travelTime = travelTimeCalculator.getLinkTravelTimes,
      volumesAnalyzer = Some(linkStatsGraph.getVolumes),
      eventTypeToNumberOfMessages = eventTypeCounter.getStats,
      carTravelTimeStats = carTravelTimeHandler.compute
    )
  }

  private def createEventManager = {
    def parallelEventManager = {
      val numberOfThreads = beamConfig.beam.physsim.eventManager.numberOfThreads
      new ParallelEventsManagerImpl(Math.max(1, numberOfThreads))
    }

    def sequentialEventManger = new EventsManagerImpl

    beamConfig.beam.physsim.eventManager.`type`.toLowerCase match {
      case "auto"       => if (beamConfig.beam.physsim.name == "PARBPRSim") sequentialEventManger else parallelEventManager
      case "sequential" => sequentialEventManger
      case "parallel"   => parallelEventManager
      case _ =>
        logger.error(
          "Wrong beam.physsim.eventManager parameter: {}. Using sequential event manger",
          beamConfig.beam.physsim.eventManager
        )
        sequentialEventManger
    }
  }

  private def getPhysSimulation(
    jdeqSimScenario: Scenario,
    jdeqsimEvents: EventsManager,
    maybeCACCSettings: Option[CACCSettings],
    maybePickUpDropOffHolder: Option[PickUpDropOffHolder],
    simName: String
  ): Mobsim = {
    val config = new JDEQSimConfigGroup
    val flowCapacityFactor = beamConfig.beam.physsim.flowCapacityFactor
    config.setFlowCapacityFactor(flowCapacityFactor)
    config.setStorageCapacityFactor(beamConfig.beam.physsim.storageCapacityFactor)
    config.setSimulationEndTime(beamConfig.matsim.modules.qsim.endTime)
    logger.info(s"Physsim name = $simName, qsim.endTime = ${config.getSimulationEndTimeAsString}")

    simName match {
      case "BPRSim" =>
        val bprCfg = BPRSimConfig(
          config.getSimulationEndTime,
          1,
          0,
          beamConfig.beam.physsim.flowCapacityFactor,
          beamConfig.beam.physsim.bprsim.inFlowAggregationTimeWindowInSeconds,
          getTravelTimeFunction(
            beamConfig.beam.physsim.bprsim.travelTimeFunction,
            beamConfig.beam.physsim.flowCapacityFactor,
            beamConfig.beam.physsim.bprsim.minFlowToUseBPRFunction,
            maybeCACCSettings,
            maybePickUpDropOffHolder
          ),
          maybeCACCSettings
        )
        new BPRSimulation(jdeqSimScenario, bprCfg, jdeqsimEvents)

      case "PARBPRSim" =>
        val numberOfClusters = beamConfig.beam.physsim.parbprsim.numberOfClusters
        if (numberOfClusters <= 0) {
          throw new IllegalArgumentException("number of clusters must be greater then zero")
        }
        val syncInterval = beamConfig.beam.physsim.parbprsim.syncInterval
        if (syncInterval <= 0) {
          throw new IllegalArgumentException("sync interval must be greater then zero")
        }
        val bprCfg = BPRSimConfig(
          config.getSimulationEndTime,
          numberOfClusters,
          syncInterval,
          beamConfig.beam.physsim.flowCapacityFactor,
          beamConfig.beam.physsim.bprsim.inFlowAggregationTimeWindowInSeconds,
          getTravelTimeFunction(
            beamConfig.beam.physsim.bprsim.travelTimeFunction,
            beamConfig.beam.physsim.flowCapacityFactor,
            beamConfig.beam.physsim.bprsim.minFlowToUseBPRFunction,
            maybeCACCSettings,
            maybePickUpDropOffHolder
          ),
          maybeCACCSettings
        )
        new ParallelBPRSimulation(jdeqSimScenario, bprCfg, jdeqsimEvents, beamConfig.matsim.modules.global.randomSeed)

      case "JDEQSim" =>
        if (maybeCACCSettings.isEmpty) {
          logger.info("CACC disabled")
        }
        new JDEQSimulation(
          config,
          beamConfig,
          jdeqSimScenario,
          jdeqsimEvents,
          maybeCACCSettings,
          maybePickUpDropOffHolder
        )

      case unknown @ _ => throw new IllegalArgumentException(s"Unknown physsim: $unknown")
    }
  }

  def createPickUpDropOffHolder(pickUpDropOffCollector: PickUpDropOffCollector): PickUpDropOffHolder =
    pickUpDropOffCollector.getPickUpDropOffHolder(beamConfig)

  def createCaccSettings(): CACCSettings = {
    logger.info("CACC enabled")
    val roadCapacityAdjustmentFunction: RoadCapacityAdjustmentFunction = new Hao2018CaccRoadCapacityAdjustmentFunction(
      beamConfig,
      agentSimIterationNumber,
      controlerIO,
      beamConfigChangesObservable
    )
    var caccCategoryRoadCount = 0
    for (link <- jdeqSimScenario.getNetwork.getLinks.values.asScala) {
      if (roadCapacityAdjustmentFunction.isCACCCategoryRoad(link)) caccCategoryRoadCount += 1
    }
    logger.info(
      "caccCategoryRoadCount: " + caccCategoryRoadCount + " out of " + jdeqSimScenario.getNetwork.getLinks.values.size
    )
    val speedAdjustmentFactor = beamConfig.beam.physsim.jdeqsim.cacc.speedAdjustmentFactor
    val adjustedMinimumRoadSpeedInMetersPerSecond =
      beamConfig.beam.physsim.jdeqsim.cacc.adjustedMinimumRoadSpeedInMetersPerSecond
    CACCSettings(
      isCACCVehicle,
      speedAdjustmentFactor,
      adjustedMinimumRoadSpeedInMetersPerSecond,
      roadCapacityAdjustmentFunction
    )
  }

  def getNoOfBins(binSize: Int): Int = {
    val endTimeStr = beamConfig.matsim.modules.qsim.endTime
    val endTime = Time.parseTime(endTimeStr)
    var numOfTimeBins = endTime / binSize
    numOfTimeBins = Math.floor(numOfTimeBins)
    numOfTimeBins.toInt + 1
  }
}

object JDEQSimRunner {

  def getTravelTimeFunction(
    functionName: String,
    flowCapacityFactor: Double,
    minVolumeToUseBPRFunction: Int,
    maybeCaccSettings: Option[CACCSettings],
    maybePickUpDropOffHolder: Option[PickUpDropOffHolder]
  ): (Double, Link, Double, Double) => Double = {
    val additionalTravelTime: (Link, Double) => Double = {
      maybePickUpDropOffHolder match {
        case Some(holder) =>
          (link, simulationTime) => holder.getAdditionalLinkTravelTime(link, simulationTime)
        case None =>
          (_, _) => 0.0
      }
    }

    functionName match {

      case "FREE_FLOW" =>
        (time, link, _, _) =>
          val originalTravelTime = link.getLength / link.getFreespeed(time)
          originalTravelTime + additionalTravelTime(link, time)
      case "BPR" =>
        maybeCaccSettings match {
          case Some(caccSettings) =>
            (time, link, caccShare, volume) => {
              val ftt = link.getLength / (link.getFreespeed(time) * caccSettings.speedAdjustmentFactor)
              if (volume >= minVolumeToUseBPRFunction) {
                val capacity = flowCapacityFactor *
                  caccSettings.roadCapacityAdjustmentFunction.getCapacityWithCACCPerSecond(link, caccShare, time)
                //volume is calculated as number of vehicles entered the road per hour
                //capacity from roadCapacityAdjustmentFunction is number of vehicles per second

                val alpha = link.getAttributes.getAttribute("alpha").toString.toDouble
                val beta = link.getAttributes.getAttribute("beta").toString.toDouble
                val tmp = volume / (capacity * 3600)
                val result = ftt * (1 + alpha * math.pow(tmp, beta))
                val originalTravelTime =
                  Math.min(result, link.getLength / caccSettings.adjustedMinimumRoadSpeedInMetersPerSecond)
                originalTravelTime + additionalTravelTime(link, time)
              } else {
                ftt + additionalTravelTime(link, time)
              }
            }
          case None =>
            (time, link, _, volume) => {
              val ftt = link.getLength / link.getFreespeed(time)
              if (volume >= minVolumeToUseBPRFunction) {
                val tmp = volume / (link.getCapacity(time) * flowCapacityFactor)
                val alpha = link.getAttributes.getAttribute("alpha").asInstanceOf[Double]
                val beta = link.getAttributes.getAttribute("beta").asInstanceOf[Double]
                val originalTravelTime = ftt * (1 + alpha * math.pow(tmp, beta))
                originalTravelTime + additionalTravelTime(link, time)
              } else {
                ftt + additionalTravelTime(link, time)
              }
            }
        }
      case unknown @ _ => throw new IllegalArgumentException(s"Unknown function name: $unknown")
    }
  }
}
