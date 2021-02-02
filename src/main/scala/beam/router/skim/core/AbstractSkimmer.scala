package beam.router.skim.core

import java.io.BufferedWriter
import java.nio.file.Paths

import beam.agentsim.events.ScalaEvent
import beam.router.skim.CsvSkimReader
import beam.sim.BeamWarmStart
import beam.sim.config.BeamConfig
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import org.matsim.core.events.handler.BasicEventHandler

import scala.collection.concurrent.TrieMap
import scala.concurrent.duration._
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

abstract class AbstractSkimmerEvent(eventTime: Double) extends Event(eventTime) with ScalaEvent {
  protected val skimName: String
  def getKey: AbstractSkimmerKey
  def getSkimmerInternal: AbstractSkimmerInternal
  def getEventType: String = skimName + "-event"
}

abstract class AbstractSkimmerReadOnly extends LazyLogging {
  private[core] var currentIterationInternal: Int = -1
  private[core] val currentSkimInternal: TrieMap[AbstractSkimmerKey, AbstractSkimmerInternal] =
    TrieMap.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
  private[core] var aggregatedFromPastSkimsInternal =
    collection.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
  private[core] val pastSkimsInternal: TrieMap[Int, collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal]] =
    TrieMap.empty[Int, collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal]]

  def currentIteration: Int = currentIterationInternal
  def currentSkim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = currentSkimInternal.readOnlySnapshot()

  def aggregatedFromPastSkims: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal] =
    aggregatedFromPastSkimsInternal

  def pastSkims: collection.Map[Int, collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal]] =
    pastSkimsInternal.readOnlySnapshot()
}

abstract class AbstractSkimmer(beamConfig: BeamConfig, ioController: OutputDirectoryHierarchy)
    extends BasicEventHandler
    with IterationStartsListener
    with IterationEndsListener
    with LazyLogging {

  protected[skim] val readOnlySkim: AbstractSkimmerReadOnly
  protected val skimFileBaseName: String
  protected val skimFileHeader: String
  protected val skimName: String
  private lazy val eventType = skimName + "-event"
  private val awaitSkimLoading = 20.minutes
  private val skimCfg = beamConfig.beam.router.skim

  import readOnlySkim._

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
    currentIterationInternal = event.getIteration
    if (currentIterationInternal == 0 && beamConfig.beam.warmStart.enabled) {
      val filePath = beamConfig.beam.warmStart.skimsFilePath
      val file = File(filePath)
      aggregatedFromPastSkimsInternal = if (file.isFile) {
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
    if (skimCfg.keepKLatestSkims > 0) {
      if (pastSkimsInternal.size >= skimCfg.keepKLatestSkims)
        pastSkimsInternal.remove(currentIterationInternal - skimCfg.keepKLatestSkims)
      pastSkimsInternal.put(currentIterationInternal, currentSkim)
    } else logger.warn("keepKLatestSkims is negative!")
    // aggregate
    if (beamConfig.beam.routing.overrideNetworkTravelTimesUsingSkims) {
      logger.warn("skim aggregation is skipped as 'overrideNetworkTravelTimesUsingSkims' enabled")
    } else {
      aggregatedFromPastSkimsInternal = (aggregatedFromPastSkimsInternal.keySet ++ currentSkimInternal.keySet).map {
        key =>
          key -> aggregateOverIterations(aggregatedFromPastSkimsInternal.get(key), currentSkimInternal.get(key))
      }.toMap
    }
    // write
    writeToDisk(event)
    // clear
    currentSkimInternal.clear()
  }

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: AbstractSkimmerEvent if e.getEventType == eventType =>
        currentSkimInternal.update(
          e.getKey,
          aggregateWithinIteration(currentSkimInternal.get(e.getKey), e.getSkimmerInternal)
        )
      case _ =>
    }
  }

  protected def writeToDisk(event: IterationEndsEvent): Unit = {
    if (skimCfg.writeSkimsInterval > 0 && currentIterationInternal % skimCfg.writeSkimsInterval == 0)
      ProfilingUtils.timed(
        s"beam.router.skim.writeSkimsInterval on iteration $currentIterationInternal",
        v => logger.info(v)
      ) {
        val filePath =
          ioController.getIterationFilename(currentIterationInternal, skimFileBaseName + ".csv.gz")
        writeSkim(currentSkim, filePath)
      }

    if (skimCfg.writeAggregatedSkimsInterval > 0 && currentIterationInternal % skimCfg.writeAggregatedSkimsInterval == 0) {
      ProfilingUtils.timed(
        s"beam.router.skim.writeAggregatedSkimsInterval on iteration $currentIterationInternal",
        v => logger.info(v)
      ) {
        val filePath =
          ioController
            .getIterationFilename(event.getServices.getIterationNumber, skimFileBaseName + "_Aggregated.csv.gz")
        writeSkim(aggregatedFromPastSkimsInternal, filePath)
      }
    }
  }

  private def writeSkim(skim: collection.Map[AbstractSkimmerKey, AbstractSkimmerInternal], filePath: String): Unit = {
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
