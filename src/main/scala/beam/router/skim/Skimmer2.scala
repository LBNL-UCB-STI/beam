package beam.router.skim
import beam.agentsim.events.ScalaEvent
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.sim.vehiclesharing.VehicleManager
import beam.utils.FileUtils
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, _}

class Skimmer2(h3taz: H3TAZ) extends AbstractBeamSkimmer2(h3taz) {
  import Skimmer2._
  private type key = (Int, Id[TAZ], String, Id[VehicleManager], String)
  private type value = Double

  val data: mutable.Map[key, value] = mutable.Map()
  var persistedData: immutable.Map[key, value] = read
  val fileName = "skim.csv.gz"

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: SkimmerEvent2 =>
        val (hexIndex, idTaz) = h3taz.getHex(e.coord.getX, e.coord.getY) match {
          case Some(hex) => (hex, h3taz.getTAZ(hex))
          case _         => ("NA", H3TAZ.emptyTAZId)
        }
        // sum values with the same Key
        data.get((e.bin, idTaz, hexIndex, e.vehMng, e.label)) match {
          case Some(value) => data.put((e.bin, idTaz, hexIndex, e.vehMng, e.label), e.value + value)
          case _           => data.put((e.bin, idTaz, hexIndex, e.vehMng, e.label), e.value)
        }
        data.put((e.bin, idTaz, hexIndex, e.vehMng, e.label), e.value)
      case _ =>
    }
  }

  override def persist(event: org.matsim.core.controler.events.IterationEndsEvent) = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      fileName
    )
    val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
    writer.write("timeBin,idTaz,hexIndex,idVehManager,label,value\n")
    data.foreach(row => writer.write(row._1.productIterator.mkString(",") + "," + row._2 + "\n"))
    writer.close()

    // persist data collected from previous iteration (no aggregation)
    persistedData = data.toMap
    data.clear()
  }

  def read(): immutable.Map[key, value] = {
    import scala.collection.JavaConverters._
    var mapReader: CsvMapReader = null
    val res = mutable.Map.empty[key, value]
    try {
      mapReader = new CsvMapReader(FileUtils.readerFromFile(fileName), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val rowMap = line.asScala.toMap
        res.put(
          (
            rowMap("timeBin").toInt,
            Id.create(rowMap("idTaz"), classOf[TAZ]),
            rowMap("hexIndex"),
            Id.create(rowMap("idVehManager"), classOf[VehicleManager]),
            rowMap("label")
          ),
          rowMap("value").toDouble
        )
        line = mapReader.read(header: _*)
      }
    } catch {
      case _: Exception => // None
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res.toMap
  }

}

object Skimmer2 {
  case class SkimmerEvent2(time: Double, bin: Int, coord: Coord, vehMng: Id[VehicleManager], label: String, value: Double)
    extends Event(time)
      with ScalaEvent {
    override def getEventType: String = "SkimmerEvent"
  }
}

