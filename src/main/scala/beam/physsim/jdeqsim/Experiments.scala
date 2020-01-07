package beam.physsim.jdeqsim

import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.sim.config.BeamConfig
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.Population
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.router.util.TravelTime

sealed abstract class Experiments(
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

// Baseline. No actual experiment here, just the same way how it used to work, but using `PhysSim` class
class Baseline(
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
) extends Experiments(
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
    val sim = new PhysSim(beamConfig, agentSimScenario, population,
      beamServices,
      controlerIO, isCACCVehicleMap, beamConfigChangesObservable, iterationNumber, shouldWritePhysSimEvents, javaRnd)
    sim.run(1, 0.0, prevTravelTime)
  }
}

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
              ) extends Experiments(
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

}