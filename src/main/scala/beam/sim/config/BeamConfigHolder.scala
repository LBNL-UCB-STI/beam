package beam.sim.config

import java.util.{Observable, Observer}

import beam.sim.{BeamConfigChangesObservable, BeamConfigChangesObserver}
import javax.inject.Inject

class BeamConfigHolder @Inject() (
  beamConfigChangesObservable: BeamConfigChangesObservable,
  config: BeamConfig
) extends BeamConfigChangesObserver {

  var beamConfig: BeamConfig = config
  beamConfigChangesObservable.addObserver(this)

  override def update(observable: BeamConfigChangesObservable, updatedBeamConfig: BeamConfig): Unit = {
    this.beamConfig = updatedBeamConfig
  }
}
