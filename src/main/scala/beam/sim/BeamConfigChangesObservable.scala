package beam.sim

import java.util
import java.util.{Observer, Vector}

import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import javax.inject.{Inject, Singleton}

import scala.collection.mutable
import scala.ref.WeakReference

@Singleton
class BeamConfigChangesObservable @Inject()(beamConfig: BeamConfig) {

  class WeaklyObservable {
    private var changed: Boolean = false
    private val observers: mutable.ListBuffer[WeakReference[BeamConfigChangesObserver]] =
      new mutable.ListBuffer[WeakReference[BeamConfigChangesObserver]]

    def setChanged(): Unit = {
      synchronized(() => changed = true)
    }

    def addObserver(observer: BeamConfigChangesObserver): Unit = {
      synchronized(() => {
        val weakObserver = new WeakReference[BeamConfigChangesObserver](observer)
        observers += weakObserver
      })
    }

    protected def clearChanged(): Unit = {
      changed = false
    }

    def notifyObservers(observable: BeamConfigChangesObservable, config: BeamConfig): Unit = {

      val aliveObservers: mutable.ListBuffer[BeamConfigChangesObserver] =
        new mutable.ListBuffer[BeamConfigChangesObserver]

      /* from java.util.Observable:
       *
       * We don't want the Observer doing callbacks into
       * arbitrary code while holding its own Monitor.
       * The code where we extract each Observable from
       * the Vector and store the state of the Observer
       * needs synchronization, but notifying observers
       * does not (should not).  The worst result of any
       * potential race-condition here is that:
       * 1) a newly-added Observer will miss a
       *   notification in progress
       * 2) a recently unregistered Observer will be
       *   wrongly notified when it doesn't care
       */
      synchronized(() => {
        if (!changed)
          return

        val links = observers.map(link => link.get)
        observers.clear()
        links.foreach {
          case Some(observer) =>
            aliveObservers += observer
            observers += WeakReference(observer)
          case None =>
        }

        clearChanged()
      })

      aliveObservers.foreach(_.update(observable, config))
    }
  }

  val observable = new WeaklyObservable()

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
    observable.setChanged()
    val updatedBeamConfig = getUpdatedBeamConfig
    lastBeamConfig = updatedBeamConfig
    BeamConfigChangesObservable.lastBeamConfigValue = updatedBeamConfig
    observable.notifyObservers(this, updatedBeamConfig)
  }

  def addObserver(observer: BeamConfigChangesObserver): Unit = observable.addObserver(observer)
}

object BeamConfigChangesObservable {

  private var lastBeamConfigValue: BeamConfig = _

  def lastBeamConfig: BeamConfig = lastBeamConfigValue

  val configFileLocationString = "configFileLocation"

  def clear(): Unit = {
    System.clearProperty(configFileLocationString)
  }
}
