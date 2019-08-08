package beam.sim

import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import javax.inject.{Inject, Singleton}

@Singleton
class BeamConfigChangesObservable @Inject()(beamConfig: BeamConfig) extends java.util.Observable {

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
    val beamConfig = getUpdatedBeamConfig
    notifyObservers(this, beamConfig)
  }
}

object BeamConfigChangesObservable {

  val configFileLocationString = "configFileLocation"

  def apply(beamConfig: BeamConfig): BeamConfigChangesObservable = new BeamConfigChangesObservable(beamConfig)

  def clear(): Unit = {
    System.clearProperty(configFileLocationString)
  }
}
