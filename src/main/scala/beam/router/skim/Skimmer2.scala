package beam.router.skim
import java.io.FileWriter
import java.util

import beam.agentsim.events.ScalaEvent
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ}
import beam.sim.vehiclesharing.VehicleManager
import beam.utils.FileUtils
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.supercsv.io.{CsvMapReader, CsvMapWriter}
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, _}

class Skimmer2(h3taz: H3TAZ) extends AbstractBeamSkimmer2(h3taz) {
  import Skimmer2._
  private type key = (Int, Id[TAZ], String, Id[VehicleManager], String)
  private type value = Double

  val data: mutable.Map[key, value] = mutable.Map()
  var persistedData: immutable.Map[key, value] = read
  val fileName = "skim2.csv.gz"

  val collectPoints = mutable.Map.empty[Coord, String]

  override def handleEvent(event: Event): Unit = {
    event match {
      case e: SkimmerEvent2 =>
        val hexIndex = h3taz.getHRHex(e.coord.getX, e.coord.getY)
        val idTaz = h3taz.getTAZ(hexIndex)
        val inTransform: GeotoolsTransformation = new GeotoolsTransformation(h3taz.scenarioEPSG, "EPSG:4326")
        val newcoord = inTransform.transform(e.coord)
        collectPoints.put(newcoord, idTaz.toString)
        // sum values with the same Key
        data.get((e.bin, idTaz, hexIndex, e.vehMng, e.label)) match {
          case Some(value) => data.put((e.bin, idTaz, hexIndex, e.vehMng, e.label), e.value + value)
          case _           => data.put((e.bin, idTaz, hexIndex, e.vehMng, e.label), e.value)
        }
        data.put((e.bin, idTaz, hexIndex, e.vehMng, e.label), e.value)
      case _ =>
    }
  }

  private def writeCsvFile(path: String, data: Seq[Seq[String]], titles: Seq[String]): Unit = {
    FileUtils.using(new CsvMapWriter(new FileWriter(path), CsvPreference.STANDARD_PREFERENCE)) { writer =>
      writer.writeHeader(titles: _*)
      val rows = data.map { row =>
        row.zipWithIndex.foldRight(new util.HashMap[String, Object]()) {
          case ((s, i), acc) =>
            acc.put(titles(i), s)
            acc
        }
      }
      val titlesArray = titles.toArray
      rows.foreach(row => writer.write(row, titlesArray: _*))
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

    writeCsvFile(event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      "pointsCollected.csv"),
      collectPoints.map(m => Seq(m._1.getX.toString, m._1.getY.toString, m._2.toString)).toSeq,
      Seq("x", "y", "status")
    )

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

