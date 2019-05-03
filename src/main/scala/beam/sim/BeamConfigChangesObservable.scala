package beam.sim

import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import javax.inject.Singleton

@Singleton
class BeamConfigChangesObservable extends java.util.Observable{

  def notifyChangeToSubscribers() {
    setChanged()
    val configFileLocation = System.getProperty(BeamConfigChangesObservable.configFileLocationString)
    val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(configFileLocation)
    val beamConfig = BeamConfig.apply(config.resolve())
    notifyObservers(this, beamConfig)
  }
}

object BeamConfigChangesObservable {

  val configFileLocationString = "configFileLocation"

  def clear(): Unit = {
    System.clearProperty(configFileLocationString)
  }
}
