package beam.router.skim

import beam.agentsim.events.ScalaEvent
import beam.sim.BeamScenario
import beam.utils.{FileUtils, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.MatsimServices
import org.matsim.core.controler.events.{IterationEndsEvent, StartupEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, StartupListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.concurrent.TrieMap
import scala.collection.{immutable, mutable}

trait BeamSkimmerKey
trait BeamSkimmerData

abstract class AbstractBeamSkimmer(val beamScenario: BeamScenario, val matsimServices: MatsimServices)
    extends BasicEventHandler
    with LazyLogging
    with StartupListener
    with IterationEndsListener {
  private var data: mutable.Map[BeamSkimmerKey, BeamSkimmerData] = mutable.Map()
  private var persistedData: immutable.Map[BeamSkimmerKey, BeamSkimmerData] = read
  protected def skimmerId: String
  protected def cvsFileHeader: String
  protected def keyDataToStrMap(keyVal: (BeamSkimmerKey, BeamSkimmerData)): immutable.Map[String, String]
  protected def strMapToKeyData(strMap: immutable.Map[String, String]): (BeamSkimmerKey, BeamSkimmerData)
  protected def mergeDataWithSameKey(storedData: BeamSkimmerData, newData: BeamSkimmerData): BeamSkimmerData
  protected def dataToPersistAtEndOfIteration(
    persistedData: immutable.Map[BeamSkimmerKey, BeamSkimmerData],
    collectedData: immutable.Map[BeamSkimmerKey, BeamSkimmerData]
  ): immutable.Map[BeamSkimmerKey, BeamSkimmerData]
  protected def checkIfDataShouldBePersistedThisIteration(iteration: Int): Boolean

  def get(key: BeamSkimmerKey) = persistedData.get(key)
  private def read: immutable.Map[BeamSkimmerKey, BeamSkimmerData] = {
    import scala.collection.JavaConverters._
    var mapReader: CsvMapReader = null
    val res = mutable.Map.empty[BeamSkimmerKey, BeamSkimmerData]
    try {
      mapReader = new CsvMapReader(FileUtils.readerFromFile(skimmerId+".csv.gz"), CsvPreference.STANDARD_PREFERENCE)
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
      skimmerId + ".csv.gz"
    )
    val writer = org.matsim.core.utils.io.IOUtils.getBufferedWriter(filePath)
    writer.write(cvsFileHeader + "\n")
    persistedData
      .map(keyDataToStrMap)
      .foreach(row => writer.write(cvsFileHeader.split(",").map(row(_)).mkString(",") + "\n"))
    writer.close()
  }
  override def handleEvent(event: Event): Unit = {
    import AbstractBeamSkimmer._
    event match {
      case e: BeamSkimmerEvent if data.contains(e.getKey) =>
        data.put(e.getKey, mergeDataWithSameKey(data(e.getKey), e.getData))
      case e: BeamSkimmerEvent => data.put(e.getKey, e.getData)
      case _                   => // None
    }
  }
  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    persistedData = dataToPersistAtEndOfIteration(persistedData, data.toMap)
    data = mutable.Map()
    if (checkIfDataShouldBePersistedThisIteration(event.getIteration)) {
      ProfilingUtils.timed(s"write to $skimmerId on iteration ${event.getIteration}", x => logger.info(x)) {
        write(event)
      }
    }
  }
  override def notifyStartup(event: StartupEvent): Unit = {
    // attach all handlers
    matsimServices.getEvents.addHandler(this)
    AbstractBeamSkimmer.skimmerMap.put(this.skimmerId, this)
  }
}

object AbstractBeamSkimmer {
  private val skimmerMap = TrieMap.empty[String, AbstractBeamSkimmer]
  // Event
  abstract class BeamSkimmerEvent(time: Double) extends Event(time) with ScalaEvent {
    def getKey: BeamSkimmerKey
    def getData: BeamSkimmerData
  }
  def get[T <: AbstractBeamSkimmer](skimmerId: String) = {
    skimmerMap.get(skimmerId)
  }
}
