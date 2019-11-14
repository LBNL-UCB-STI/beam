package beam.router.skim

import java.io.{BufferedWriter, File}

import beam.agentsim.events.ScalaEvent
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.{IterationEndsEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, StartupListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, mutable}
import scala.util.control.NonFatal

trait AbstractSkimmerKey {
  def toCsv: String
}

trait AbstractSkimmerInternal {
  def aggregateOverIterations(iterations: Int, newSkim: Option[_ <: AbstractSkimmerInternal]): AbstractSkimmerInternal
  def aggregateByKey(newSkim: Option[_ <: AbstractSkimmerInternal]): AbstractSkimmerInternal
  def toCsv: String
}

abstract class AbstractSkimmerEvent(eventTime: Double, beamServices: BeamServices)
    extends Event(eventTime)
    with ScalaEvent {
  def getKey: AbstractSkimmerKey
  def getSkimmerInternal: AbstractSkimmerInternal
}

abstract class AbstractSkimmerReadOnly(beamServices: BeamServices) extends LazyLogging {
  protected[skim] val pastSkims: mutable.ListBuffer[immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]] =
    mutable.ListBuffer()
  protected[skim] var aggregatedSkim: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = immutable.Map()
}

abstract class AbstractSkimmer(beamServices: BeamServices, config: BeamConfig.Beam.Router.Skim.Skimmers$Elm)
    extends BasicEventHandler
    with StartupListener
    with IterationEndsListener
    with LazyLogging {
  import beamServices._

  protected[skim] val readOnlySkim: AbstractSkimmerReadOnly
  protected val skimFileBaseName: String
  protected val skimFileHeader: String
  protected def fromCsv(line: immutable.Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal)

  protected val currentSkim: mutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = mutable.Map()

  override def notifyStartup(event: StartupEvent): Unit = {
    readOnlySkim.aggregatedSkim = readAggregatedSkims
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    writeToDisk(event)
    // keep in memory
    if (beamConfig.beam.router.skim.keepKLatestSkims > 0) {
      if (readOnlySkim.pastSkims.size == beamConfig.beam.router.skim.keepKLatestSkims) {
        readOnlySkim.pastSkims.dropRight(1)
      }
      readOnlySkim.pastSkims.prepend(currentSkim.toMap)
    }
    // aggregate
    beamConfig.beam.router.skim.aggregateFunction match {
      case "LATEST_SKIM" =>
        readOnlySkim.aggregatedSkim = readOnlySkim.pastSkims.head
      case "AVG" =>
        readOnlySkim.aggregatedSkim = aggregateAVG(event.getIteration)
      case _ => // default
        logger.info("no aggregation function is chosen for the skimmers!")
    }
    currentSkim.clear()
  }

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: AbstractSkimmerEvent if e.getEventType == config.skimType =>
        currentSkim.put(e.getKey, e.getSkimmerInternal.aggregateByKey(currentSkim.get(e.getKey)))
      case _ =>
    }
  }

  protected def writeToDisk(event: IterationEndsEvent) = {
    if (beamConfig.beam.router.skim.writeSkimsInterval > 0 && event.getIteration % beamConfig.beam.router.skim.writeSkimsInterval == 0)
      ProfilingUtils.timed(s"beam.router.skim.writeSkimsInterval on iteration ${event.getIteration}", logger.info(_)) {
        val filePath =
          beamServices.matsimServices.getControlerIO
            .getIterationFilename(event.getServices.getIterationNumber, skimFileBaseName + ".csv.gz")
        writeSkim(currentSkim.toMap, filePath)
      }

    if (beamConfig.beam.router.skim.writeAggregatedSkimsInterval > 0 && event.getIteration % beamConfig.beam.router.skim.writeAggregatedSkimsInterval == 0) {
      ProfilingUtils.timed(
        s"beam.router.skim.writeAggregatedSkimsInterval on iteration ${event.getIteration}", logger.info(_)
      ) {
        val filePath =
          beamServices.matsimServices.getControlerIO
            .getIterationFilename(event.getServices.getIterationNumber, skimFileBaseName + "Aggregated.csv.gz")
        writeSkim(readOnlySkim.aggregatedSkim, filePath)
      }
    }
  }

  // ***
  // Helpers
  private def aggregateAVG(numIterations: Int): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    val avgSkim = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    readOnlySkim.aggregatedSkim.foreach {
      case (k, aggSkim) =>
        avgSkim.put(k, aggSkim.aggregateOverIterations(numIterations, currentSkim.get(k)))
    }
    currentSkim.foreach(skim => avgSkim.put(skim._1, skim._2.aggregateOverIterations(numIterations, None)))
    avgSkim.toMap
  }

  private def readAggregatedSkims: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    var mapReader: CsvMapReader = null
    val res = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    val aggregatedSkimsFilePath = skimFileBaseName + "Aggregated.csv.gz"
    if (beamConfig.beam.warmStart.enabled) {
      try {
        if (new File(aggregatedSkimsFilePath).isFile) {
          mapReader =
            new CsvMapReader(FileUtils.readerFromFile(aggregatedSkimsFilePath), CsvPreference.STANDARD_PREFERENCE)
          val header = mapReader.getHeader(true)
          var line: java.util.Map[String, String] = mapReader.read(header: _*)
          while (null != line) {
            import scala.collection.JavaConverters._
            val newPair = fromCsv(line.asScala.toMap)
            res.put(newPair._1, newPair._2)
            line = mapReader.read(header: _*)
          }
          logger.info(s"warmStart skim successfully loaded from path '${aggregatedSkimsFilePath}'")
        } else {
          logger.info(s"warmStart skim NO PATH FOUND '${aggregatedSkimsFilePath}'")
        }
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not load warmStart skim from '${aggregatedSkimsFilePath}': ${ex.getMessage}", ex)
      } finally {
        if (null != mapReader)
          mapReader.close()
      }
    }
    res.toMap
  }

  private def writeSkim(skim: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal], filePath: String) = {
    var writer: BufferedWriter = null
    try {
      writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
      writer.write(skimFileHeader + "\n")
      skim.foreach(row => writer.write(row._1.toCsv + "," + row._2.toCsv + "\n"))
      writer.close()
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not write skim in '${filePath}': ${ex.getMessage}", ex)
    } finally {
      if (null != writer)
        writer.close()
    }
  }
}
