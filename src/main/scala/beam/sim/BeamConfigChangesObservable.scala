package beam.sim

import beam.sim.config.BeamConfig
import beam.utils.BeamConfigUtils
import com.typesafe.scalalogging.LazyLogging

import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.ref.WeakReference

@Singleton
class BeamConfigChangesObservable @Inject() (
  beamConfig: BeamConfig,
  maybeOriginalConfigLocation: Option[String] = None
) {

  class WeaklyObservable extends LazyLogging {
    private var changed: Boolean = false

    private val observers: mutable.ListBuffer[WeakReference[BeamConfigChangesObserver]] =
      new mutable.ListBuffer[WeakReference[BeamConfigChangesObserver]]

    def setChanged(): Unit = {
      this.synchronized { changed = true }
    }

    def addObserver(observer: BeamConfigChangesObserver): Unit = {
      this.synchronized {
        val weakObserver = new WeakReference[BeamConfigChangesObserver](observer)
        observers += weakObserver
      }
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

      this.synchronized {
        if (!changed)
          return

        observers.map(link => link.get).foreach {
          case Some(observer) => aliveObservers += observer
          case None           =>
        }

        logger.debug(s"There are ${observers.size} observers and ${aliveObservers.size} of them alive.")

        clearChanged()
      }

      aliveObservers.foreach(_.update(observable, config))
    }
  }

  private val observable = new WeaklyObservable()

  var lastBeamConfig: BeamConfig = beamConfig

  private def updateLastBeamConfigFromOriginalConfigLocation(): Unit = {
    lastBeamConfig = maybeOriginalConfigLocation match {
      case Some(location) =>
        val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(location)
        BeamConfig.apply(config.resolve())
      case None =>
        beamConfig
    }
  }

  def updateBeamConfigAndNotifyChangeToSubscribers(): Unit = {
    observable.setChanged()
    updateLastBeamConfigFromOriginalConfigLocation()
    observable.notifyObservers(this, lastBeamConfig)
  }

  def addObserver(observer: BeamConfigChangesObserver): Unit = observable.addObserver(observer)
}
