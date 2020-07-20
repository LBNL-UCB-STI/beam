package beam.physsim.jdeqsim

import java.util.Random
import java.{lang, util}

import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.router.util.TravelTime

sealed abstract class RelaxationExperiment(
  val beamConfig: BeamConfig,
  val agentSimScenario: Scenario,
  val population: Population,
  val beamServices: BeamServices,
  val controllerIO: OutputDirectoryHierarchy,
  val isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  val beamConfigChangesObservable: BeamConfigChangesObservable,
  val iterationNumber: Int,
  val shouldWritePhysSimEvents: Boolean,
  val javaRnd: java.util.Random
) {
  def run(prevTravelTime: TravelTime): TravelTime
}

object RelaxationExperiment extends LazyLogging {

  def apply(
    beamConfig: BeamConfig,
    agentSimScenario: Scenario,
    population: Population,
    beamServices: BeamServices,
    controlerIO: OutputDirectoryHierarchy,
    isCACCVehicle: util.Map[String, lang.Boolean],
    beamConfigChangesObservable: BeamConfigChangesObservable,
    iterationNumber: Int,
    javaRnd: Random
  ): RelaxationExperiment = {
    val `type` = beamConfig.beam.physsim.relaxation.`type`
    val writePhysSimEvents = shouldWritePhysSimEvents(beamConfig.beam.physsim.writeEventsInterval, iterationNumber)
    `type` match {
      case "normal" | "consecutive_increase_of_population" =>
        new Normal(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case "experiment_2.0" =>
        new Experiment_2_0(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case "experiment_2.1" =>
        new Experiment_2_1(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case "experiment_3.0" =>
        new Experiment_3_0(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case "experiment_4.0" =>
        new Experiment_4_0(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case "experiment_5.0" =>
        new Experiment_5_0(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case "experiment_5.1" =>
        new Experiment_5_1(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case "experiment_5.2" =>
        new Experiment_5_2(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
      case _ =>
        logger.warn(s"beam.physsim.relaxation.type = '${`type`}' which is unknown. Will use normal")
        new Normal(
          beamConfig,
          agentSimScenario,
          population,
          beamServices,
          controlerIO,
          isCACCVehicle,
          beamConfigChangesObservable,
          iterationNumber,
          writePhysSimEvents,
          javaRnd
        )
    }
  }

  private def shouldWritePhysSimEvents(interval: Int, iterationNumber: Int): Boolean = {
    interval == 1 || (interval > 0 && iterationNumber % interval == 0)
  }
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
// Approx.PhysSim starting with 10% population up to 100
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
      javaRnd,
      beamConfig.beam.physsim.relaxation.experiment4_0.percentToSimulate match {
        case Some(list) => list.toArray
        case None       => Array(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0)
      }
    )
    sim.run(prevTravelTime)
  }
}

// Experiment 5.0: Clear modes and routes only in the beginning of the 1 iteration of AgentSim.
// Approx.PhysSim starting with 10% population up to 100, but only after 0 iteration. In all other cases use normal physsim only once
class Experiment_5_0(
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
    if (iterationNumber == 0) {
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
        javaRnd,
        beamConfig.beam.physsim.relaxation.experiment4_0.percentToSimulate match {
          case Some(list) => list.toArray
          case None       => Array(10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0, 10.0)
        }
      )
      sim.run(prevTravelTime)
    } else {
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
      sim.run(1, 0, prevTravelTime)
    }
  }
}

// Experiment 5.1: Clear modes and routes only in the beginning of the 1 iteration of AgentSim.
// Approx.PhysSim starting with 60% population up to 100 every 10% [60, 10, 10, 10, 10], but only after 0 iteration. In all other cases use normal physsim only once
//
class Experiment_5_1(
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
    if (iterationNumber == 0) {
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
        javaRnd,
        beamConfig.beam.physsim.relaxation.experiment5_1.percentToSimulate match {
          case Some(list) => list.toArray
          case None       => Array(60.0, 10.0, 10.0, 10.0, 10.0)
        }
      )
      sim.run(prevTravelTime)
    } else {
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
      sim.run(1, 0, prevTravelTime)
    }
  }
}

// Experiment 5.2: Clear modes and routes only in the beginning of the 1 iteration of AgentSim.
// Approx.PhysSim starting with 40% population up to 100 every 20% [40, 60, 80, 100], but only after 0 iteration. In all other cases use normal physsim only once
//
class Experiment_5_2(
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
    if (iterationNumber == 0) {
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
        javaRnd,
        beamConfig.beam.physsim.relaxation.experiment4_0.percentToSimulate match {
          case Some(list) => list.toArray
          case None       => Array(40.0, 20.0, 20.0, 20.0)
        }
      )
      sim.run(prevTravelTime)
    } else {
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
      sim.run(1, 0, prevTravelTime)
    }
  }
}

// Experiment **5.1:
// - Is the same as Experiment 5.0, but no cleaning of routes (Push back the routes created by the PhysSim to the people.)
// After 10 iterations approx physsim we have relaxed network. Also we have plans and routes for the population
// If we copy plans for the personal cars to the agentsim (make sure that replanning part are accordingly adjusted not to touch those plans anymore)

// There can be different options with clearing the modes (clear only 30% of modes and other things)
// Three types of vehicels:
// - Person car. This one only has a plan
// - RHM
// - CAV
