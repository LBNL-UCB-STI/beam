package beam.physsim.jdeqsim

import java.util.Random
import java.{lang, util}

import beam.physsim.PickUpDropOffCollector
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.router.util.TravelTime

/**
  * RelaxationExperiment is an abstract base class for different traffic simulation
  * relaxation approaches. Relaxation refers to the process of iteratively improving
  * route choices in a traffic network to reach a more realistic traffic equilibrium.
  * Results are here => https://github.com/LBNL-UCB-STI/beam/issues/2371
  */
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
  val javaRnd: java.util.Random,
  val maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
) {

  /**
    * Run the relaxation experiment with the provided previous travel time.
    * @param prevTravelTime TravelTime from previous iteration
    * @return SimulationResult containing updated travel times and statistics
    */
  def run(prevTravelTime: TravelTime): SimulationResult
}

object RelaxationExperiment extends LazyLogging {

  /**
    * Factory method that creates the appropriate RelaxationExperiment implementation
    * based on the configuration.
    */
  def apply(
    beamConfig: BeamConfig,
    agentSimScenario: Scenario,
    population: Population,
    beamServices: BeamServices,
    controlerIO: OutputDirectoryHierarchy,
    isCACCVehicle: util.Map[String, lang.Boolean],
    beamConfigChangesObservable: BeamConfigChangesObservable,
    iterationNumber: Int,
    javaRnd: Random,
    maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
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
          javaRnd,
          maybePickUpDropOffCollector
        )
    }
  }

  private def shouldWritePhysSimEvents(interval: Int, iterationNumber: Int): Boolean = {
    interval == 1 || (interval > 0 && iterationNumber % interval == 0)
  }
}

/**
  * Normal experiment (baseline implementation)
  *
  * This is the standard implementation with no experimental features.
  * It simply runs the PhysSim simulation once with no special routing or
  * population adjustments. This serves as the baseline against which other
  * experiments can be compared.
  *
  * Key characteristics:
  * - Single iteration of JDEQSim
  * - No rerouting of vehicles
  * - Standard population sampling
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
      javaRnd,
      maybePickUpDropOffCollector
    )
    sim.run(1, 0.0, prevTravelTime)
  }
}

/**
  * Experiment 2.0: Multiple JDEQSim iterations with rerouting
  *
  * This experiment runs multiple iterations of JDEQSim with rerouting
  * a fraction of the population between iterations. This approach aims
  * to achieve better route choices by giving vehicles the opportunity
  * to find better routes based on the congestion patterns observed in
  * previous iterations.
  *
  * Key characteristics:
  * - Runs multiple internal iterations of JDEQSim (default 15)
  * - For each iteration, reroutes a configurable fraction of the population
  * - "Cleans" modes and routes between iterations to allow for fresh routing decisions
  *
  * Configuration options:
  * - internalNumberOfIterations: how many iterations of JDEQSim to run
  * - fractionOfPopulationToReroute: what percentage of vehicles to reroute each iteration
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
      javaRnd,
      maybePickUpDropOffCollector
    )
    val numOfPhysSimIters = beamConfig.beam.physsim.relaxation.experiment2_0.internalNumberOfIterations
    val fractionOfPopulationToReroute = beamConfig.beam.physsim.relaxation.experiment2_0.fractionOfPopulationToReroute
    sim.run(numOfPhysSimIters, fractionOfPopulationToReroute, prevTravelTime)
  }
}

/**
  * Experiment 2.1: Single JDEQSim iteration with rerouting
  *
  * This experiment is similar to Experiment 2.0 but designed to use fewer iterations.
  * It still reroutes a fraction of the population but uses just one iteration of JDEQSim
  * instead of multiple iterations. This approach tests whether significant benefits can
  * be achieved with less computational effort.
  *
  * Key characteristics:
  * - Typically runs a single iteration of JDEQSim
  * - Reroutes a configurable fraction of the population
  * - "Cleans" modes and routes like Experiment 2.0
  *
  * Configuration options:
  * - internalNumberOfIterations: typically set to 1
  * - fractionOfPopulationToReroute: what percentage of vehicles to reroute
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
      javaRnd,
      maybePickUpDropOffCollector
    )
    val numOfPhysSimIters = beamConfig.beam.physsim.relaxation.experiment2_1.internalNumberOfIterations
    val fractionOfPopulationToReroute = beamConfig.beam.physsim.relaxation.experiment2_1.fractionOfPopulationToReroute
    sim.run(numOfPhysSimIters, fractionOfPopulationToReroute, prevTravelTime)
  }
}

/**
  * Experiment 3.0: Front-loaded relaxation
  *
  * This experiment performs intensive relaxation only in the first iteration of AgentSim.
  * The hypothesis is that most of the benefits of relaxation can be achieved in the first
  * iteration, so subsequent iterations can use simpler, less computationally intensive
  * approaches.
  *
  * Key characteristics:
  * - For iteration 0 (first iteration): runs multiple JDEQSim iterations with rerouting
  * - For all subsequent iterations: runs a single JDEQSim iteration without rerouting
  * - "Cleans" modes and routes only at the beginning of the first iteration
  *
  * Configuration options:
  * - internalNumberOfIterations: number of JDEQSim iterations to run in the first AgentSim iteration
  * - fractionOfPopulationToReroute: what percentage to reroute in the first iteration
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
      javaRnd,
      maybePickUpDropOffCollector
    )
    val numOfPhysSimIters =
      if (iterationNumber == 0) beamConfig.beam.physsim.relaxation.experiment3_0.internalNumberOfIterations else 1
    val fractionOfPopulationToReroute =
      if (iterationNumber == 0) beamConfig.beam.physsim.relaxation.experiment3_0.fractionOfPopulationToReroute else 0.0
    sim.run(numOfPhysSimIters, fractionOfPopulationToReroute, prevTravelTime)
  }
}

/**
  * Experiment 4.0: Approximate PhysSim with gradual population increase
  *
  * This experiment uses a different simulation approach called "ApproxPhysSim" that
  * gradually increases the percentage of the population being simulated. This approach
  * aims to find a good traffic equilibrium more efficiently by first establishing
  * patterns with a smaller population, then gradually refining with larger populations.
  *
  * Key characteristics:
  * - Uses ApproxPhysSim instead of standard PhysSim
  * - Starts with a small percentage of the population (default 10%)
  * - Gradually increases to 100% over multiple internal iterations
  * - "Cleans" modes and routes only at the beginning of the first iteration
  *
  * Configuration options:
  * - percentToSimulate: Array of percentages determining how much of the population
  *   to simulate in each step (defaults to ten 10% steps)
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
      },
      maybePickUpDropOffCollector
    )
    sim.run(prevTravelTime)
  }
}

/**
  * Experiment 5.0: First iteration approximation with later simplification
  *
  * This experiment combines approaches from previous experiments. It uses the
  * approximate approach with gradual population increase (like Experiment 4.0)
  * but only in the first iteration. For all other iterations, it uses the
  * standard PhysSim approach with a single iteration and no rerouting.
  *
  * Key characteristics:
  * - For iteration 0: Uses ApproxPhysSim with gradual population increase
  * - For all other iterations: Uses standard PhysSim with single iteration and no rerouting
  * - Combines the efficiency benefits of ApproxPhysSim with the stability of standard PhysSim
  *
  * Configuration options:
  * - Uses percentToSimulate from experiment4_0 for the first iteration
  * - Default is ten 10% steps (from 10% to 100%)
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
        },
        maybePickUpDropOffCollector
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
        javaRnd,
        maybePickUpDropOffCollector
      )
      sim.run(1, 0, prevTravelTime)
    }
  }
}

/**
  * Experiment 5.1: Larger initial population with first iteration approximation
  *
  * This experiment is a variation of Experiment 5.0 with different population percentages.
  * Instead of starting with 10% of the population, it starts with 60% and then adds
  * 10% increments. This tests whether starting with a larger initial population
  * provides better results while still benefiting from the gradual increase approach.
  *
  * Key characteristics:
  * - For iteration 0: Uses ApproxPhysSim with gradual population increase
  * - Starts with 60% population (vs 10% in Experiment 5.0)
  * - Default percentages are [60.0, 10.0, 10.0, 10.0, 10.0] (totaling 100%)
  * - For all other iterations: Uses standard PhysSim with single iteration and no rerouting
  *
  * Configuration options:
  * - percentToSimulate: Configuration for the population percentages in each step
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
        },
        maybePickUpDropOffCollector
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
        javaRnd,
        maybePickUpDropOffCollector
      )
      sim.run(1, 0, prevTravelTime)
    }
  }
}

/**
  * Experiment 5.2: Medium initial population with larger increments
  *
  * This experiment is another variation of Experiment 5.0 with different population
  * percentages and larger increments. It starts with 40% of the population and uses
  * 20% increments. This tests whether larger increments provide comparable results
  * with fewer steps, potentially reducing computational time.
  *
  * Key characteristics:
  * - For iteration 0: Uses ApproxPhysSim with gradual population increase
  * - Starts with 40% population
  * - Uses larger 20% increments to reach 100%
  * - Default percentages are [40.0, 20.0, 20.0, 20.0] (totaling 100%)
  * - For all other iterations: Uses standard PhysSim with single iteration and no rerouting
  *
  * Configuration options:
  * - Uses percentToSimulate from experiment4_0 for configuration
  */
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
  javaRnd: java.util.Random,
  maybePickUpDropOffCollector: Option[PickUpDropOffCollector]
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
      javaRnd,
      maybePickUpDropOffCollector
    ) {

  override def run(prevTravelTime: TravelTime): SimulationResult = {
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
        },
        maybePickUpDropOffCollector
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
        javaRnd,
        maybePickUpDropOffCollector
      )
      sim.run(1, 0, prevTravelTime)
    }
  }
}

/**
  * Potential Future Experiments (Draft Notes)
  *
  * Experiment 5.1 variant:
  * - Same as Experiment 5.0, but without cleaning routes
  * - Push back the routes created by PhysSim to the people
  * - After 10 iterations of approx physsim, network should be relaxed
  * - Plans and routes would be preserved for the population
  * - Could copy plans for personal cars to the agentsim
  * - Would require adjusting replanning to not modify these plans
  *
  * Other potential variations:
  * - Different options for clearing modes (clear only 30% of modes)
  * - Special handling for different vehicle types:
  *   - Person car (has only a plan)
  *   - RHM (Ride Hailing/Mobility)
  *   - CAV (Connected Autonomous Vehicles)
  */
