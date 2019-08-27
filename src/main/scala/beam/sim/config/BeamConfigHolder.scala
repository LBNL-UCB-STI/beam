package beam.sim.config
import java.util.{Observable, Observer}

import beam.sim.BeamConfigChangesObservable
import javax.inject.Inject
import org.matsim.core.config.groups.ControlerConfigGroup

class BeamConfigHolder @Inject()(
  beamConfigChangesObservable: BeamConfigChangesObservable,
  config: BeamConfig,
  controlerConfigGroup: ControlerConfigGroup
) extends Observer {

  var beamConfig: BeamConfig = config
  beamConfigChangesObservable.addObserver(this)

  override def update(o: Observable, arg: Any): Unit = {
    val (_, updatedBeamConfig) = arg.asInstanceOf[(_, BeamConfig)]
    this.beamConfig = updatedBeamConfig

    controlerConfigGroup.setWritePlansInterval(beamConfig.beam.physsim.writePlansInterval)
  }
}
