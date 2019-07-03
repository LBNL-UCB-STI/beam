package beam.sim
import beam.agentsim.events.ScalaEvent
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.controler.listener.IterationEndsListener
import org.matsim.core.events.handler.BasicEventHandler
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, mutable}

trait BeamObserverKey
trait BeamObserverData
trait BeamObserverEvent extends Event with ScalaEvent {
  def getKey: BeamObserverKey
  def getData: BeamObserverData
}

abstract class BeamObserver(beamScenario: BeamScenario) extends BasicEventHandler with LazyLogging {
  protected var data: mutable.Map[BeamObserverKey, BeamObserverData] = mutable.Map()
  protected var persistedData: immutable.Map[BeamObserverKey, BeamObserverData] = read
  //protected var beamServicesOption: Option[BeamServices] = None
  protected def cvsFileHeader: String
  protected def cvsFileName: String
  protected def keyDataToStrMap(keyVal: (BeamObserverKey, BeamObserverData)): immutable.Map[String, String]
  protected def strMapToKeyData(strMap: immutable.Map[String, String]): (BeamObserverKey, BeamObserverData)
//  def register(beamServices: BeamServices) = {
//    beamServicesOption = Some(beamServices)
//  }
  def get(key: BeamObserverKey) = persistedData.get(key)
  private def read: immutable.Map[BeamObserverKey, BeamObserverData] = {
    import scala.collection.JavaConverters._
    val mapReader = new CsvMapReader(FileUtils.readerFromFile(cvsFileName), CsvPreference.STANDARD_PREFERENCE)
    val res = mutable.Map.empty[BeamObserverKey, BeamObserverData]
    try {
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val (key, value) = strMapToKeyData(line.asScala.toMap)
        res.put(key, value)
        line = mapReader.read(header: _*)
      }
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res.toMap
  }
  private def write(event: org.matsim.core.controler.events.IterationEndsEvent) = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      cvsFileName + ".csv.gz"
    )
    val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
    writer.write(cvsFileHeader+"\n")
    persistedData.map(keyDataToStrMap).foreach(row =>
      writer.write(cvsFileHeader.split(",").map(row(_)).mkString(",")+"\n")
    )
    writer.close()
  }
  override def handleEvent(event: Event): Unit = {
    event match {
      case e: BeamObserverEvent => data.put(e.getKey, e.getData)
      case _ => // None
    }
  }
  def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    persistedData = data.toMap
    data = mutable.Map()
    if (beamScenario.beamConfig.beam.outputs.writeSkimsInterval > 0 && event.getIteration % beamScenario.beamConfig.beam.outputs.writeSkimsInterval == 0) {
      ProfilingUtils.timed(s"write to $cvsFileName on iteration ${event.getIteration}", x => logger.info(x)) {
        write(event)
      }
    }
  }

}
