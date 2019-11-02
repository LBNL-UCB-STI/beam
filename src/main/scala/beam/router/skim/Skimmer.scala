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

  val aggregatedSkimsFilePath: String = beamConfig.beam.warmStart.skimsPlusFilePath
  val aggregatedSkimsFileBaseName: String = "skimsAggregated.csv.gz"
  val observedSkimsFileBaseName: String = "skims.csv.gz"
  val CsvLineHeader: String = "timeBin,idTaz,hexIndex,idVehManager,label,value"

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: SkimmerEvent =>
        val hexIndex = h3taz.getHRHex(e.coord.getX, e.coord.getY)
        val idTaz = h3taz.getTAZ(hexIndex)
        // sum values with the same Key
        currentSkim.get(SkimmerKey(e.bin, idTaz, hexIndex, e.vehMng, e.label)) match {
          case Some(internal) => currentSkim.put(SkimmerKey(e.bin, idTaz, hexIndex, e.vehMng, e.label), SkimmerInternal(e.value + internal.value))
          case _           => currentSkim.put(SkimmerKey(e.bin, idTaz, hexIndex, e.vehMng, e.label), SkimmerInternal(e.value))
        }
        currentSkim.put(SkimmerKey(e.bin, idTaz, hexIndex, e.vehMng, e.label), SkimmerInternal(e.value))
      case _ =>
    }
  }

  override def writeToDisk(event: IterationEndsEvent): Unit = {
    if (beamConfig.beam.skimmanager.skimmer.writeSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.skimmer.writeSkimsInterval == 0) {
      ProfilingUtils.timed(s"beam.skimmanager.skimmer.writeSkimsInterval on iteration ${event.getIteration}", x => logger.info(x)) {
        val filePath = event.getServices.getControlerIO.getIterationFilename(
          event.getServices.getIterationNumber,
          observedSkimsFileBaseName
        )
        val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
        writer.write(CsvLineHeader + Eol)
        currentSkim.foreach(row => writer.write(row._1.toCsv + "," + row._2 + Eol))
        writer.close()
      }
    }

    if (beamConfig.beam.skimmanager.skimmer.writeAggregatedSkimsInterval > 0 && event.getIteration % beamConfig.beam.skimmanager.skimmer.writeAggregatedSkimsInterval == 0) {
      ProfilingUtils.timed(s"beam.skimmanager.skimmer.writeAggregatedSkimsInterval on iteration ${event.getIteration}", x => logger.info(x)) {
        val filePath = event.getServices.getControlerIO.getIterationFilename(
          event.getServices.getIterationNumber,
          aggregatedSkimsFileBaseName
        )
        val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
        writer.write(CsvLineHeader + Eol)
        currentSkim.foreach(row => writer.write(row._1.toCsv+ "," + row._2 + Eol))
        writer.close()
      }
    }
  }

  override def fromCsv(line: immutable.Map[String, String]): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    immutable.Map(
      SkimmerKey(
        line("timeBin").toInt,
        Id.create(line("idTaz"), classOf[TAZ]),
        line("hexIndex"),
        Id.create(line("idVehManager"), classOf[VehicleManager]),
        line("label"))
        -> SkimmerInternal(line("value").toDouble)
    )
  }

}

object Skimmer {
  case class SkimmerKey(
    timeBin: Int,
    idTaz: Id[TAZ],
    hexIndex: String,
    idVehManager: Id[VehicleManager],
    valueLabel: String) extends AbstractSkimmerKey {
    override def toCsv: String = timeBin + "," + idTaz + "," + hexIndex + "," + idVehManager + "," + valueLabel
  }
  case class SkimmerInternal(value: Double) extends AbstractSkimmerInternal {
    def +(that: AbstractSkimmerInternal): AbstractSkimmerInternal = SkimmerInternal(this.value + that.asInstanceOf[SkimmerInternal].value)
    def /(thatInt: Int): AbstractSkimmerInternal = SkimmerInternal(this.value / thatInt)
    def *(thatInt: Int): AbstractSkimmerInternal = SkimmerInternal(this.value * thatInt)
    override def toCsv: String = value.toString
  }

  case class SkimmerEvent(time: Double, bin: Int, coord: Coord, vehMng: Id[VehicleManager], label: String, value: Double)
    extends Event(time)
      with ScalaEvent {
    override def getEventType: String = "SkimmerEvent"
  }
}

