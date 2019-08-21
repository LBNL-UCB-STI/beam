package beam.sim

import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import javax.inject.{Inject, Singleton}

@Singleton
class BeamConfigChangesObservable @Inject()(beamConfig: BeamConfig) extends java.util.Observable {

  var lastBeamConfig: BeamConfig = beamConfig
  BeamConfigChangesObservable.lastBeamConfigValue = beamConfig

  def getUpdatedBeamConfig: BeamConfig = {
    val configFileLocation = System.getProperty(BeamConfigChangesObservable.configFileLocationString)
    Option(configFileLocation) match {
      case Some(location) =>
        val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(location)
        BeamConfig.apply(config.resolve())
      case None =>
        beamConfig
    }
  }

  def notifyChangeToSubscribers() {
    setChanged()
    val updatedBeamConfig = getUpdatedBeamConfig
    lastBeamConfig = updatedBeamConfig
    BeamConfigChangesObservable.lastBeamConfigValue = updatedBeamConfig
    notifyObservers(this, updatedBeamConfig)
  }

}

object BeamConfigChangesObservable {

  private var lastBeamConfigValue: BeamConfig = _

  def lastBeamConfig: BeamConfig = lastBeamConfigValue

  val configFileLocationString = "configFileLocation"

  def clear(): Unit = {
    System.clearProperty(configFileLocationString)
  }

}
