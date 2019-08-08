package beam.utils
import java.nio.file._
import java.nio.file.StandardWatchEventKinds._
import java.util.concurrent.{ExecutorService, Executors}

import beam.sim.BeamConfigChangesObservable
import com.google.common.util.concurrent.ThreadFactoryBuilder
import javax.inject.{Inject, Singleton}

import scala.collection.JavaConverters._
import scala.concurrent.{ExecutionContext, Future}

@Singleton
class BeamConfigChangesPublisher @Inject()(beamConfigChangesObservable: BeamConfigChangesObservable) {

  private val execSvc: ExecutorService = Executors.newFixedThreadPool(
    1,
    new ThreadFactoryBuilder().setDaemon(true).setNameFormat("logging-events-manager-%d").build()
  )
  private val executionContext: ExecutionContext = ExecutionContext.fromExecutorService(execSvc)

  private val file = Paths.get(System.getProperty(BeamConfigChangesObservable.configFileLocationString)).getParent

  private val watcher = FileSystems.getDefault.newWatchService()
  file.register(watcher, ENTRY_MODIFY)

  Future {
    while (true) {
      val key = watcher.take
      key.pollEvents().asScala.foreach {
        _.kind() match {
          case ENTRY_MODIFY =>
            beamConfigChangesObservable.notifyChangeToSubscribers()
          case _ =>
        }
      }
      key.reset()
    }
  }(executionContext)

}
