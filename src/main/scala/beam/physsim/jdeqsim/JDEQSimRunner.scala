package beam.physsim.jdeqsim

import beam.analysis.physsim.{PhyssimCalcLinkStats, PhyssimSpeedHandler}
import beam.analysis.plot.PlotGraph
import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.{
  Hao2018CaccRoadCapacityAdjustmentFunction,
  RoadCapacityAdjustmentFunction
}
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation
import beam.sim.BeamConfigChangesObservable
import beam.sim.config.BeamConfig
import beam.utils.{DebugLib, ProfilingUtils}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.analysis.LegHistogram
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.trafficmonitoring.TravelTimeCalculator
import org.matsim.core.utils.misc.Time

import scala.collection.JavaConverters._
import scala.util.Try
import scala.util.control.NonFatal

class JDEQSimRunner(
  val beamConfig: BeamConfig,
  val jdeqSimScenario: Scenario,
  val population: Population,
  val controlerIO: OutputDirectoryHierarchy,
  val isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  val beamConfigChangesObservable: BeamConfigChangesObservable,
  val agentSimIterationNumber: Int
) extends StrictLogging {

  def simulate(currentPhysSimIter: Int, writeEvents: Boolean): SimulationResult = {
    val jdeqsimEvents = new EventsManagerImpl
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
      beamConfig,
      jdeqSimScenario.getConfig.travelTimeCalculator,
      beamConfigChangesObservable
    )
    linkStatsGraph.notifyIterationStarts(jdeqsimEvents, jdeqSimScenario.getConfig.travelTimeCalculator)

    val eventTypeCounter = new EventTypeCounter
    jdeqsimEvents.addHandler(eventTypeCounter)
    val carTravelTimeHandler = new CarTravelTimeHandler(isCACCVehicle.asScala.map {
      case (k, v) => k -> Boolean2boolean(v)
    })
    jdeqsimEvents.addHandler(carTravelTimeHandler)

    jdeqsimEvents.addHandler(travelTimeCalculator)
    jdeqsimEvents.addHandler(new JDEQSimMemoryFootprint(beamConfig.beam.debug.debugEnabled))

    val physsimSpeedHandler = new PhyssimSpeedHandler(population, controlerIO, beamConfig)
    jdeqsimEvents.addHandler(physsimSpeedHandler)

    val maybeEventWriter = if (writeEvents) {
      val writer = PhysSimEventWriter(beamConfig, controlerIO, jdeqsimEvents, currentPhysSimIter)
      jdeqsimEvents.addHandler(writer)
      Some(writer)
    } else None

    val maybeRoadCapacityAdjustmentFunction = if (beamConfig.beam.physsim.jdeqsim.cacc.enabled) {
      Some(
        new Hao2018CaccRoadCapacityAdjustmentFunction(
          beamConfig,
          agentSimIterationNumber,
          controlerIO,
          beamConfigChangesObservable
        )
      )
    } else None

    try {
      ProfilingUtils.timed(
        s"JDEQSim iteration $currentPhysSimIter for ${population.getPersons.size()} people",
        x => logger.info(x)
      ) {
        val jdeqSimulation = getJDEQSimulation(jdeqSimScenario, jdeqsimEvents, maybeRoadCapacityAdjustmentFunction)
        logger.info(s"JDEQSim iteration $currentPhysSimIter start")
        if (beamConfig.beam.debug.debugEnabled) {
          logger.info(DebugLib.getMemoryLogMessage("Memory Use Before JDEQSim: "))
        }
        jdeqSimulation.run()
        logger.info(s"JDEQSim iteration $currentPhysSimIter finished")
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Failed in JDEQSim: ${ex.getMessage}", ex)
    } finally {
      Try(jdeqsimEvents.finishProcessing())
      maybeEventWriter.foreach { wrt =>
        Try(wrt.closeFile())
      }
      maybeRoadCapacityAdjustmentFunction.foreach(_.reset())

      legHistogram.getLegModes.forEach(mode => {
        new PlotGraph().writeGraphic(
          legHistogram,
          controlerIO,
          s"${currentPhysSimIter}.physsimTripHistogram",
          "time (binSize=<?> sec)",
          mode,
          agentSimIterationNumber,
          beamConfig.beam.outputs.stats.binSize
        )
      })
      linkStatsGraph.notifyIterationEnds(agentSimIterationNumber, travelTimeCalculator.getLinkTravelTimes)
      physsimSpeedHandler.notifyIterationEnds(agentSimIterationNumber)
    }
    SimulationResult(
      iteration = currentPhysSimIter,
      travelTime = travelTimeCalculator.getLinkTravelTimes,
      eventTypeToNumberOfMessages = eventTypeCounter.getStats,
      carTravelTimeStats = carTravelTimeHandler.compute
    )
  }

  private def getJDEQSimulation(
    jdeqSimScenario: Scenario,
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
        val caccSettings = CACCSettings(isCACCVehicle, roadCapacityAdjustmentFunction)
        val speedAdjustmentFactor = beamConfig.beam.physsim.jdeqsim.cacc.speedAdjustmentFactor
        val adjustedMinimumRoadSpeedInMetersPerSecond =
          beamConfig.beam.physsim.jdeqsim.cacc.adjustedMinimumRoadSpeedInMetersPerSecond
        new JDEQSimulation(
          config,
          jdeqSimScenario,
          jdeqsimEvents,
          caccSettings,
          speedAdjustmentFactor,
          adjustedMinimumRoadSpeedInMetersPerSecond
        )

      case None =>
        logger.info("CACC disabled")
        new org.matsim.core.mobsim.jdeqsim.JDEQSimulation(config, jdeqSimScenario, jdeqsimEvents)
    }
  }

  def getNoOfBins(binSize: Int): Int = {
    val endTimeStr = beamConfig.matsim.modules.qsim.endTime
    val endTime = Time.parseTime(endTimeStr)
    var numOfTimeBins = endTime / binSize
    numOfTimeBins = Math.floor(numOfTimeBins)
    numOfTimeBins.toInt + 1
  }
}
