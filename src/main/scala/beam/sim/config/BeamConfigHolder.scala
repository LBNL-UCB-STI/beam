package beam.sim.config
import java.util.{Observable, Observer}

import beam.sim.BeamConfigChangesObservable
import javax.inject.Inject

class BeamConfigHolder @Inject()(
  beamConfigChangesObservable: BeamConfigChangesObservable,
  config: BeamConfig
) extends Observer {

  var beamConfig: BeamConfig = config
  beamConfigChangesObservable.addObserver(this)

  override def update(o: Observable, arg: Any): Unit = {
    val (_, updatedBeamConfig) = arg.asInstanceOf[(_, BeamConfig)]
    this.beamConfig = updatedBeamConfig
  }
}
