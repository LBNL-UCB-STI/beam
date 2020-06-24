package beam.router.skim

import java.io.BufferedWriter
import java.nio.file.Paths

import beam.agentsim.events.ScalaEvent
import beam.sim.{BeamServices, BeamWarmStart}
import beam.sim.config.BeamConfig
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.{immutable, mutable}
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.reflect.io.File
import scala.util.control.NonFatal

trait AbstractSkimmerKey {
  def toCsv: String
}

trait AbstractSkimmerInternal {
  val observations: Int
  val iterations: Int

  def toCsv: String
}

abstract class AbstractSkimmerEvent(eventTime: Double, beamServices: BeamServices)
    extends Event(eventTime)
    with ScalaEvent {
  protected val skimName: String

  def getKey: AbstractSkimmerKey

  def getSkimmerInternal: AbstractSkimmerInternal

  def getEventType: String = skimName + "-event"
}

abstract class AbstractSkimmerReadOnly extends LazyLogging {
  protected[skim] val pastSkims: mutable.ListBuffer[Map[AbstractSkimmerKey, AbstractSkimmerInternal]] =
    mutable.ListBuffer()
  protected[skim] var aggregatedSkim: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = immutable.Map()
}

abstract class AbstractSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim)
    extends BasicEventHandler
    with IterationStartsListener
    with IterationEndsListener
    with LazyLogging {

  import beamServices._

  protected[skim] val readOnlySkim: AbstractSkimmerReadOnly
  protected val skimFileBaseName: String
  protected val skimFileHeader: String
  protected val skimName: String
  protected lazy val currentSkim = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
  private lazy val eventType = skimName + "-event"

  private val awaitSkimLoading = 20.minutes

  protected def fromCsv(line: scala.collection.Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal)
  protected def aggregateOverIterations(
    prevIteration: Option[AbstractSkimmerInternal],
    currIteration: Option[AbstractSkimmerInternal]
  ): AbstractSkimmerInternal

  protected def aggregateWithinIteration(
    prevObservation: Option[AbstractSkimmerInternal],
    currObservation: AbstractSkimmerInternal
  ): AbstractSkimmerInternal

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    if (event.getIteration == 0 && beamConfig.beam.warmStart.enabled) {
      val filePath = beamConfig.beam.warmStart.skimsFilePath
      val file = File(filePath)
      readOnlySkim.aggregatedSkim = if (file.isFile) {
        new CsvSkimReader(filePath, fromCsv, logger).readAggregatedSkims
      } else {
        val filePattern = s"*${BeamWarmStart.fileNameSubstringToDetectIfReadSkimsInParallelMode}*.csv*"
        FileUtils
          .flatParRead(Paths.get(file.path), filePattern, awaitSkimLoading) { (path, reader) =>
            new CsvSkimReader(path.toString, fromCsv, logger).readSkims(reader)
          }
          .toMap
      }
    }
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    // keep in memory
    if (beamConfig.beam.router.skim.keepKLatestSkims > 0) {
      if (readOnlySkim.pastSkims.size == beamConfig.beam.router.skim.keepKLatestSkims) {
        readOnlySkim.pastSkims.dropRight(1)
      }
      readOnlySkim.pastSkims.prepend(currentSkim.toMap)
    }
    // aggregate
    readOnlySkim.aggregatedSkim = (readOnlySkim.aggregatedSkim.keySet ++ currentSkim.keySet).map { key =>
      key -> aggregateOverIterations(readOnlySkim.aggregatedSkim.get(key), currentSkim.get(key))
    }.toMap
    // write
    writeToDisk(event)
    // clear
    currentSkim.clear()
  }

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: AbstractSkimmerEvent if e.getEventType == eventType =>
        currentSkim.update(e.getKey, aggregateWithinIteration(currentSkim.get(e.getKey), e.getSkimmerInternal))
      case _ =>
    }
  }

  protected def writeToDisk(event: IterationEndsEvent): Unit = {
    if (beamConfig.beam.router.skim.writeSkimsInterval > 0 && event.getIteration % beamConfig.beam.router.skim.writeSkimsInterval == 0)
      ProfilingUtils.timed(
        s"beam.router.skim.writeSkimsInterval on iteration ${event.getIteration}",
        v => logger.info(v)
      ) {
        val filePath =
          beamServices.matsimServices.getControlerIO
            .getIterationFilename(event.getServices.getIterationNumber, skimFileBaseName + ".csv.gz")
        writeSkim(currentSkim.toMap, filePath)
      }

    if (beamConfig.beam.router.skim.writeAggregatedSkimsInterval > 0 && event.getIteration % beamConfig.beam.router.skim.writeAggregatedSkimsInterval == 0) {
      ProfilingUtils.timed(
        s"beam.router.skim.writeAggregatedSkimsInterval on iteration ${event.getIteration}",
        v => logger.info(v)
      ) {
        val filePath =
          beamServices.matsimServices.getControlerIO
            .getIterationFilename(event.getServices.getIterationNumber, skimFileBaseName + "_Aggregated.csv.gz")
        writeSkim(readOnlySkim.aggregatedSkim, filePath)
      }
    }
  }

  private def writeSkim(skim: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal], filePath: String): Unit = {
    var writer: BufferedWriter = null
    try {
      writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
      writer.write(skimFileHeader + "\n")
      skim.foreach(row => writer.write(row._1.toCsv + "," + row._2.toCsv + "\n"))
      writer.close()
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write skim in '$filePath': ${ex.getMessage}", ex)
    } finally {
      if (null != writer)
        writer.close()
    }
  }
}
