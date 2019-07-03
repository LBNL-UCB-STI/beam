package beam.sim
import beam.agentsim.events.ScalaEvent
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.events.IterationEndsEvent
import org.matsim.core.events.handler.BasicEventHandler
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, mutable}

trait BeamObserverKey
trait BeamObserverData

abstract class BeamObserver(beamScenario: BeamScenario) extends BasicEventHandler with LazyLogging {
  private var data: mutable.Map[BeamObserverKey, BeamObserverData] = mutable.Map()
  private var persistedData: immutable.Map[BeamObserverKey, BeamObserverData] = read
  protected def cvsFileHeader: String
  protected def cvsFileName: String
  protected def keyDataToStrMap(keyVal: (BeamObserverKey, BeamObserverData)): immutable.Map[String, String]
  protected def strMapToKeyData(strMap: immutable.Map[String, String]): (BeamObserverKey, BeamObserverData)
  protected def mergeDataWithSameKey(storedData: BeamObserverData, newData: BeamObserverData): BeamObserverData
  protected def dataToPersistAtEndOfIteration(
    persistedData: immutable.Map[BeamObserverKey, BeamObserverData],
    collectedData: immutable.Map[BeamObserverKey, BeamObserverData]
  ): immutable.Map[BeamObserverKey, BeamObserverData]
  protected def checkIfDataShouldBePersistedThisIteration(iteration: Int): Boolean

  def get(key: BeamObserverKey) = persistedData.get(key)
  private def read: immutable.Map[BeamObserverKey, BeamObserverData] = {
    import scala.collection.JavaConverters._
    var mapReader: CsvMapReader = null
    val res = mutable.Map.empty[BeamObserverKey, BeamObserverData]
    try {
      mapReader = new CsvMapReader(FileUtils.readerFromFile(cvsFileName), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val (key, value) = strMapToKeyData(line.asScala.toMap)
        res.put(key, value)
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
  private def write(event: org.matsim.core.controler.events.IterationEndsEvent) = {
    val filePath = event.getServices.getControlerIO.getIterationFilename(
      event.getServices.getIterationNumber,
      cvsFileName + ".csv.gz"
    )
    val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
    writer.write(cvsFileHeader + "\n")
    persistedData
      .map(keyDataToStrMap)
      .foreach(row => writer.write(cvsFileHeader.split(",").map(row(_)).mkString(",") + "\n"))
    writer.close()
  }
  override def handleEvent(event: Event): Unit = {
    import BeamObserver._
    event match {
      case e: BeamObserverEvent if data.contains(e.getKey) =>
        data.put(e.getKey, mergeDataWithSameKey(data(e.getKey), e.getData))
      case e: BeamObserverEvent => data.put(e.getKey, e.getData)
      case _                    => // None
    }
  }

  def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    persistedData = dataToPersistAtEndOfIteration(persistedData, data.toMap)
    data = mutable.Map()
    if (checkIfDataShouldBePersistedThisIteration(event.getIteration)) {
      ProfilingUtils.timed(s"write to $cvsFileName on iteration ${event.getIteration}", x => logger.info(x)) {
        write(event)
      }
    }
  }
}

object BeamObserver {
  // Event
  abstract class BeamObserverEvent(time: Double) extends Event(time) with ScalaEvent {
    def getKey: BeamObserverKey
    def getData: BeamObserverData
  }
}
