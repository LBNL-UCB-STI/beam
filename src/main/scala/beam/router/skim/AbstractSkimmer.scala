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

  private var observationsCounter: Int = 0
  protected val currentSkim: mutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = mutable.Map()
  updateAggregatedSkim(readAggregatedSkims)

  protected val aggregatedSkimsFilePath: String
  protected def writeToDisk(event: IterationEndsEvent)
  protected def fromCsv(line: immutable.Map[String, String]): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]
  protected def getPastSkims: immutable.List[immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]]
  protected def updatePastSkims(skims: immutable.List[immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]])
  protected def getAggregatedSkim: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]
  protected def updateAggregatedSkim(skim: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal])

  private[skim] def persist(event: IterationEndsEvent) = {
    writeToDisk(event)
    // keep in memory
    if (beamConfig.beam.skimmanager.keepTheNLastestSkims > 0) {
      if (getPastSkims.size == beamConfig.beam.skimmanager.keepTheNLastestSkims) {
        updatePastSkims(currentSkim.toMap :: getPastSkims.dropRight(1))
      } else {
        updatePastSkims(currentSkim.toMap :: getPastSkims)
      }
    }
    // aggregate
    beamConfig.beam.skimmanager.aggregateFunction match {
      case "LATEST_SKIM" =>
        updateAggregatedSkim(getPastSkims.head)
      case "AVG" =>
        updateAggregatedSkim(aggregateAVG())
      case _ => // default
    }
    observationsCounter += 1
    currentSkim.clear()
  }

  // ***
  // Helpers
  private def aggregateAVG(): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    val avgSkim = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    getAggregatedSkim.foreach {
      case (k, aggSkim) =>
        currentSkim.get(k) match {
          case Some(curSkim) =>
            avgSkim.put(k, ((aggSkim * observationsCounter) + curSkim) / (observationsCounter + 1))
            currentSkim.remove(k)
          case _ =>
            avgSkim.put(k, (aggSkim * observationsCounter) / (observationsCounter + 1))
        }
    }
    currentSkim.foreach {
      case (k, curSkim) =>
        avgSkim.put(k, curSkim / (observationsCounter + 1))
    }
    avgSkim.toMap
  }

  private def readAggregatedSkims: immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    var mapReader: CsvMapReader = null
    var res = immutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    if (beamConfig.beam.warmStart.enabled) {
      try {
        if (new File(aggregatedSkimsFilePath).isFile) {
          mapReader =
            new CsvMapReader(FileUtils.readerFromFile(aggregatedSkimsFilePath), CsvPreference.STANDARD_PREFERENCE)
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
