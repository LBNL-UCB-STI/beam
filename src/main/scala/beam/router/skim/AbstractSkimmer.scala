package beam.router.skim

import java.io.File

import beam.agentsim.infrastructure.taz.H3TAZ
import beam.sim.BeamServices
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.events.handler.BasicEventHandler
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, mutable}
import scala.util.control.NonFatal

trait AbstractSkimmerKey {
  def toCsv: String
}
trait AbstractSkimmerInternal {
  def +(that: AbstractSkimmerInternal): AbstractSkimmerInternal
  def /(thatInt: Int): AbstractSkimmerInternal
  def *(thatInt: Int): AbstractSkimmerInternal
  def toCsv: String
}

abstract class AbstractSkimmer(beamServices: BeamServices, h3taz: H3TAZ) extends BasicEventHandler with LazyLogging {
  import beamServices._

  protected val currentSkim: mutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = mutable.Map()
  protected var pastSkims: immutable.List[immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]] = immutable.List()
  protected var aggregatedSkim: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = readAggregatedSkims
  private var observationsCounter: Int = 0
  protected val Eol = "\n"

  val aggregatedSkimsFilePath: String
  protected def writeToDisk(event: IterationEndsEvent)
  protected def fromCsv(line: immutable.Map[String, String]): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]


  def persist(event: IterationEndsEvent) = {

    writeToDisk(event)

    // keep in memory
    if(beamConfig.beam.skimmanager.keepTheNLastestSkims > 0) {
      if (pastSkims.size == beamConfig.beam.skimmanager.keepTheNLastestSkims) {
        pastSkims = currentSkim.toMap :: pastSkims.dropRight(1)
      } else {
        pastSkims = currentSkim.toMap :: pastSkims
      }
    }

    // aggregate
    beamConfig.beam.skimmanager.aggregateFunction match {
      case "LATEST_SKIM" =>
        aggregatedSkim = pastSkims.head
      case "AVG" =>
        aggregatedSkim = aggregateAVG()
      case _ => // default
    }

    observationsCounter += 1
    currentSkim.clear()
  }

  private def aggregateAVG(): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    val avgSkim = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    aggregatedSkim.foreach { case (k, aggSkim) =>
      currentSkim.get(k) match {
        case Some(curSkim) =>
          avgSkim.put(k, ((aggSkim * observationsCounter) + curSkim) / (observationsCounter + 1))
          currentSkim.remove(k)
        case _ =>
          avgSkim.put(k, (aggSkim * observationsCounter) / (observationsCounter + 1))
      }
    }
    currentSkim.foreach { case (k, curSkim) =>
      avgSkim.put(k, curSkim/(observationsCounter + 1))
    }
    avgSkim.toMap
  }

  private def readAggregatedSkims: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    var mapReader: CsvMapReader = null
    var res = immutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    if (beamConfig.beam.warmStart.enabled) {
      try {
        if (new File(aggregatedSkimsFilePath).isFile) {
          mapReader = new CsvMapReader(FileUtils.readerFromFile(aggregatedSkimsFilePath), CsvPreference.STANDARD_PREFERENCE)
          val header = mapReader.getHeader(true)
          var line: java.util.Map[String, String] = mapReader.read(header: _*)
          while (null != line) {
            import scala.collection.JavaConverters._
            res ++= fromCsv(line.asScala.toMap)
            line = mapReader.read(header: _*)
          }
          logger.info(s"warmstart skim successfully loaded from path '${aggregatedSkimsFilePath}'")
        } else {
          logger.info(s"warmstart skim NO PATH FOUND '${aggregatedSkimsFilePath}'")
        }
      } catch {
        case NonFatal(ex) =>
          logger.error(s"Could not load warmstart skim from '${aggregatedSkimsFilePath}': ${ex.getMessage}", ex)
      } finally {
        if (null != mapReader)
          mapReader.close()
      }
    }
    res
  }

}
