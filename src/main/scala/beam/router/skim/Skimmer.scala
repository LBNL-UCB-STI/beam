package beam.router.skim
import beam.agentsim.events.ScalaEvent
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.sim.BeamServices
import beam.sim.vehiclesharing.VehicleManager
import beam.utils.ProfilingUtils
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.events.IterationEndsEvent

import scala.collection.immutable

class Skimmer(beamServices: BeamServices, h3taz: H3TAZ) extends AbstractSkimmer(beamServices, h3taz) {
  import Skimmer._
  import beamServices._

  private val aggregatedSkimsFileBaseName: String = "skimsAggregated.csv.gz"
  private val observedSkimsFileBaseName: String = "skims2.csv.gz"
  private val CsvLineHeader: String = "timeBin,idTaz,hexIndex,idVehManager,label,dblValue"
  private val Eol = "\n"

  Skimmer.h3taz = h3taz

  override val aggregatedSkimsFilePath: String = beamConfig.beam.warmStart.skimsPlusFilePath

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: SkimmerEvent =>
        val hexIndex = h3taz.getHRHex(e.coord.getX, e.coord.getY)
        val idTaz = h3taz.getTAZ(hexIndex)
        // sum values with the same Key
        currentSkim.get(SkimmerKey(e.timeBin, idTaz, hexIndex, e.idVehMng, e.valLabel)) match {
          case Some(internal: SkimmerInternal) =>
            currentSkim.put(
              SkimmerKey(e.timeBin, idTaz, hexIndex, e.idVehMng, e.valLabel),
              SkimmerInternal(e.dblValue + internal.dblValue)
            )
          case _ =>
            currentSkim.put(SkimmerKey(e.timeBin, idTaz, hexIndex, e.idVehMng, e.valLabel), SkimmerInternal(e.dblValue))
        }
        currentSkim.put(SkimmerKey(e.timeBin, idTaz, hexIndex, e.idVehMng, e.valLabel), SkimmerInternal(e.dblValue))
      case _ =>
    }
  }

  override def writeToDisk(event: IterationEndsEvent): Unit = {
    if (beamConfig.beam.skimmanager.skimmer.writeSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.skimmer.writeSkimsInterval == 0) {
      ProfilingUtils.timed(
        s"beam.skimmanager.skimmer.writeSkimsInterval on iteration ${event.getIteration}",
        x => logger.info(x)
      ) {
        val filePath = event.getServices.getControlerIO.getIterationFilename(
          event.getServices.getIterationNumber,
          observedSkimsFileBaseName
        )
        val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
        writer.write(CsvLineHeader + Eol)
        currentSkim.foreach(row => writer.write(row._1.toCsv + "," + row._2.toCsv + Eol))
        writer.close()
      }
    }

    if (beamConfig.beam.skimmanager.skimmer.writeAggregatedSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.skimmer.writeAggregatedSkimsInterval == 0) {
      ProfilingUtils.timed(
        s"beam.skimmanager.skimmer.writeAggregatedSkimsInterval on iteration ${event.getIteration}",
        x => logger.info(x)
      ) {
        val filePath = event.getServices.getControlerIO.getIterationFilename(
          event.getServices.getIterationNumber,
          aggregatedSkimsFileBaseName
        )
        val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
        writer.write(CsvLineHeader + Eol)
        currentSkim.foreach(row => writer.write(row._1.toCsv + "," + row._2 + Eol))
        writer.close()
      }
    }
  }

  override def fromCsv(
    line: immutable.Map[String, String]
  ): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    immutable.Map(
      SkimmerKey(
        line("timeBin").toInt,
        Id.create(line("idTaz"), classOf[TAZ]),
        line("hexIndex"),
        Id.create(line("idVehManager"), classOf[VehicleManager]),
        line("label")
      )
      -> SkimmerInternal(line("dblValue").toDouble)
    )
  }

  override protected def getPastSkims: List[Map[AbstractSkimmerKey, AbstractSkimmerInternal]] = {
    Skimmer.pastSkims.map(
      list => list.map(kv => kv._1.asInstanceOf[AbstractSkimmerKey] -> kv._2.asInstanceOf[AbstractSkimmerInternal])
    )
  }

  override protected def getAggregatedSkim: Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    Skimmer.aggregatedSkim.map(
      kv => kv._1.asInstanceOf[AbstractSkimmerKey] -> kv._2.asInstanceOf[AbstractSkimmerInternal]
    )
  }

  override protected def updatePastSkims(skims: List[Map[AbstractSkimmerKey, AbstractSkimmerInternal]]): Unit = {
    Skimmer.pastSkims =
      skims.map(list => list.map(kv => kv._1.asInstanceOf[SkimmerKey] -> kv._2.asInstanceOf[SkimmerInternal]))
  }

  override protected def updateAggregatedSkim(skim: Map[AbstractSkimmerKey, AbstractSkimmerInternal]): Unit = {
    Skimmer.aggregatedSkim = skim.map(kv => kv._1.asInstanceOf[SkimmerKey] -> kv._2.asInstanceOf[SkimmerInternal])
  }
}

object Skimmer {

  private var pastSkims: immutable.List[immutable.Map[SkimmerKey, SkimmerInternal]] = immutable.List()
  private var aggregatedSkim: immutable.Map[SkimmerKey, SkimmerInternal] = immutable.Map()
  private var h3taz: H3TAZ = _

  def getLatestSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    valLabel: String
  ): Option[SkimmerInternal] = {
    pastSkims.headOption
      .flatMap(_.get(SkimmerKey(timeBin, idTaz, hexIndex, idVehMng, valLabel)))
      .asInstanceOf[Option[SkimmerInternal]]
  }

  def getLatestSkim(timeBin: Int, hexIndex: String, idVehMng: Id[VehicleManager], valLabel: String): Option[SkimmerInternal] = {
    getLatestSkim(timeBin, h3taz.getTAZ(hexIndex), hexIndex, idVehMng, valLabel)
  }

  def getLatestSkim(timeBin: Int, idTaz: Id[TAZ], idVehMng: Id[VehicleManager], valLabel: String): Option[SkimmerInternal] = {
    h3taz.getHRHex(idTaz).flatMap(hexIndex => getLatestSkim(timeBin, idTaz, hexIndex, idVehMng, valLabel)).foldLeft[Option[SkimmerInternal]](None) {
      case (acc, skimInternal) =>
      acc match {
        case Some(skim) => Some((skim + skimInternal).asInstanceOf[SkimmerInternal])
        case _ => Some(skimInternal)
      }
    }
  }

  def getAggregatedSkim(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehMng: Id[VehicleManager],
    valLabel: String
  ): Option[SkimmerInternal] = {
    aggregatedSkim
      .get(SkimmerKey(timeBin, idTaz, hexIndex, idVehMng, valLabel))
      .asInstanceOf[Option[SkimmerInternal]]
  }

  // Cases
  case class SkimmerKey(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehManager: Id[VehicleManager],
    valueLabel: String
  ) extends AbstractSkimmerKey {
    override def toCsv: String = timeBin + "," + idTaz + "," + hexIndex + "," + idVehManager + "," + valueLabel
  }

  case class SkimmerInternal(dblValue: Double) extends AbstractSkimmerInternal {

    def +(that: AbstractSkimmerInternal): AbstractSkimmerInternal =
      SkimmerInternal(this.dblValue + that.asInstanceOf[SkimmerInternal].dblValue)
    def /(thatInt: Int): AbstractSkimmerInternal = SkimmerInternal(this.dblValue / thatInt)
    def *(thatInt: Int): AbstractSkimmerInternal = SkimmerInternal(this.dblValue * thatInt)
    override def toCsv: String = dblValue.toString
  }

  case class SkimmerEvent(
                           eventTime: Double,
                           timeBin: Int,
                           coord: Coord,
                           idVehMng: Id[VehicleManager],
                           valLabel: String,
                           dblValue: Double
  ) extends Event(eventTime)
      with ScalaEvent {
    override def getEventType: String = "SkimmerEvent"
  }

}
