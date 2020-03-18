package beam.physsim.jdeqsim

import beam.physsim.jdeqsim.cacc.CACCSettings
import beam.physsim.jdeqsim.cacc.roadCapacityAdjustmentFunctions.{
  Hao2018CaccRoadCapacityAdjustmentFunction,
  RoadCapacityAdjustmentFunction
}
import beam.physsim.jdeqsim.cacc.sim.JDEQSimulation
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.sim.config.BeamConfig
import beam.utils.{DebugLib, ProfilingUtils}
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.mobsim.jdeqsim.JDEQSimConfigGroup
import org.matsim.core.trafficmonitoring.TravelTimeCalculator

import scala.util.Try

import scala.collection.JavaConverters._

class JDEQSimRunner(
  val beamConfig: BeamConfig,
  val jdeqSimScenario: Scenario,
  val population: Population,
  val beamServices: BeamServices,
  val controlerIO: OutputDirectoryHierarchy,
  val isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  val beamConfigChangesObservable: BeamConfigChangesObservable,
  val iterationNumber: Int
) extends StrictLogging {

  def simulate(currentPhysSimIter: Int, writeEvents: Boolean): SimulationResult = {
    val jdeqsimEvents = new EventsManagerImpl
    val travelTimeCalculator =
      new TravelTimeCalculator(jdeqSimScenario.getNetwork, jdeqSimScenario.getConfig.travelTimeCalculator)

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
        s"JDEQSim iteration $currentPhysSimIter for ${population.getPersons.size()} people",
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
}
