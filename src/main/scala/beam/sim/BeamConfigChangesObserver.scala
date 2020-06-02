package beam.sim

import beam.sim.config.BeamConfig

trait BeamConfigChangesObserver {
  def update(observable: BeamConfigChangesObservable, updatedBeamConfig: BeamConfig): Unit
}
