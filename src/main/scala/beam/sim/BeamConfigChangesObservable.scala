package beam.sim

import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import javax.inject.Singleton

@Singleton
class BeamConfigChangesObservable extends java.util.Observable{
  def notifyChangeToSubscribers() {
    setChanged()
    val configFileLocation = System.getProperty("configFileLocation")
    val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(configFileLocation) //get path from system variables
    val beamConfig = BeamConfig.apply(config.resolve())
    notifyObservers(this, beamConfig)
  }
}
