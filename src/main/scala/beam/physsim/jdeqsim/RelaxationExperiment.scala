package beam.physsim.jdeqsim

import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.router.util.TravelTime

sealed abstract class RelaxationExperiment(
  val beamConfig: BeamConfig,
  val agentSimScenario: Scenario,
  val population: Population,
  val beamServices: BeamServices,
  val controlerIO: OutputDirectoryHierarchy,
  val isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  val beamConfigChangesObservable: BeamConfigChangesObservable,
  val iterationNumber: Int,
  val shouldWritePhysSimEvents: Boolean,
  val javaRnd: java.util.Random
) {
  def run(prevTravelTime: TravelTime): TravelTime
}

// Normal. No actual experiment here, just the same way how it used to work, but using `PhysSim` class
class Normal(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controlerIO: OutputDirectoryHierarchy,
  isCACCVehicleMap: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  iterationNumber: Int,
  shouldWritePhysSimEvents: Boolean,
  javaRnd: java.util.Random
) extends RelaxationExperiment(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    ) {
  override def run(prevTravelTime: TravelTime): TravelTime = {
    val sim = new PhysSim(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    )
    sim.run(1, 0.0, prevTravelTime)
  }
}

// Experiment 2.0: Clean modes and routes, 15 iteration of JDEQSim
class Experiment_2_0(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controlerIO: OutputDirectoryHierarchy,
  isCACCVehicleMap: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  iterationNumber: Int,
  shouldWritePhysSimEvents: Boolean,
  javaRnd: java.util.Random
) extends RelaxationExperiment(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    ) {
  override def run(prevTravelTime: TravelTime): TravelTime = {
    val sim = new PhysSim(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    )
    val numOfPhysSimIters = beamConfig.beam.physsim.relaxation.experiment2_0.internalNumberOfIterations
    val fractionOfPopulationToReroute = beamConfig.beam.physsim.relaxation.experiment2_0.fractionOfPopulationToReroute
    sim.run(numOfPhysSimIters, fractionOfPopulationToReroute, prevTravelTime)
  }
}

// Experiment 2.1: Clean modes and routes, 1 iteration of JDEQSim
class Experiment_2_1(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controlerIO: OutputDirectoryHierarchy,
  isCACCVehicleMap: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  iterationNumber: Int,
  shouldWritePhysSimEvents: Boolean,
  javaRnd: java.util.Random
) extends RelaxationExperiment(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    ) {
  override def run(prevTravelTime: TravelTime): TravelTime = {
    val sim = new PhysSim(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    )
    val numOfPhysSimIters = beamConfig.beam.physsim.relaxation.experiment2_1.internalNumberOfIterations
    val fractionOfPopulationToReroute = beamConfig.beam.physsim.relaxation.experiment2_1.fractionOfPopulationToReroute
    sim.run(numOfPhysSimIters, fractionOfPopulationToReroute, prevTravelTime)
  }
}

// Experiment 3.0: Clear modes and routes only in the beginning of the 1 iteration of AgentSim, 15 iterations of JDEQSim is only after 0 iteration of AgentSim
class Experiment_3_0(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controlerIO: OutputDirectoryHierarchy,
  isCACCVehicleMap: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  iterationNumber: Int,
  shouldWritePhysSimEvents: Boolean,
  javaRnd: java.util.Random
) extends RelaxationExperiment(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    ) {
  override def run(prevTravelTime: TravelTime): TravelTime = {
    val sim = new PhysSim(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    )
    val numOfPhysSimIters =
      if (iterationNumber == 0) beamConfig.beam.physsim.relaxation.experiment3_0.internalNumberOfIterations else 1
    val fractionOfPopulationToReroute =
      if (iterationNumber == 0) beamConfig.beam.physsim.relaxation.experiment3_0.fractionOfPopulationToReroute else 0.0
    sim.run(numOfPhysSimIters, fractionOfPopulationToReroute, prevTravelTime)
  }
}

// Experiment 4.0: Clear modes and routes only in the beginning of the 1 iteration of AgentSim.
// Approx.PhysSim starting with 10% population up to 100, but only after iteration 0 of AgentSim.
class Experiment_4_0(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controlerIO: OutputDirectoryHierarchy,
  isCACCVehicleMap: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  iterationNumber: Int,
  shouldWritePhysSimEvents: Boolean,
  javaRnd: java.util.Random
) extends RelaxationExperiment(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    ) {
  override def run(prevTravelTime: TravelTime): TravelTime = {
    val sim = new ApproxPhysSim(
      beamConfig,
      agentSimScenario,
      population,
      beamServices,
      controlerIO,
      isCACCVehicleMap,
      beamConfigChangesObservable,
      iterationNumber,
      shouldWritePhysSimEvents,
      javaRnd
    )
    sim.run(prevTravelTime)
  }
}
