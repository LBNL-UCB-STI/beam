package beam.sim

import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import javax.inject.Singleton

@Singleton
class BeamConfigChangesObservable extends java.util.Observable{

  def getUpdatedBeamConfig: BeamConfig = {
    val configFileLocation = System.getProperty(BeamConfigChangesObservable.configFileLocationString)
    val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(configFileLocation)
    BeamConfig.apply(config.resolve())
  }

  def notifyChangeToSubscribers() {
    setChanged()
    val beamConfig = getUpdatedBeamConfig
    notifyObservers(this, beamConfig)
  }
}

object BeamConfigChangesObservable {

  val configFileLocationString = "configFileLocation"

  def clear(): Unit = {
    System.clearProperty(configFileLocationString)
  }
}
