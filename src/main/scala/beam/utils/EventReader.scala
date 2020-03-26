package beam.utils

import java.io._
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util
import java.util.zip.GZIPInputStream

import beam.agentsim.events._
import org.matsim.api.core.v01.events.{Event, GenericEvent}
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.config.Config
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.utils.io.UnicodeInputStream
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable.ArrayBuffer
import scala.reflect.ClassTag

class DummyEvent(attribs: java.util.Map[String, String]) extends Event(attribs.get("time").toDouble) {
  override def getEventType: String = attribs.get("type")

  override def getAttributes: util.Map[String, String] = attribs
}

object EventReader {

  def fromCsvFile(filePath: String, filterPredicate: Event => Boolean): (Iterator[Event], Closeable) = {
    val rdr = if (filePath.startsWith("http://") || filePath.startsWith("https://")) {
      val stream = {
        val s = new URL(filePath).openStream()
        if (filePath.endsWith(".gz")) {
          new GZIPInputStream(s)
        } else {
          s
        }
      }
      new BufferedReader(new InputStreamReader(new UnicodeInputStream(stream), StandardCharsets.UTF_8))
    } else {
      FileUtils.readerFromFile(filePath)
    }
    fromCsvReader(rdr, filterPredicate)
  }

  def fromCsvReader(rdr: Reader, filterPredicate: Event => Boolean): (Iterator[Event], Closeable) = {
    readAs[Event](rdr, x => new DummyEvent(x), filterPredicate)
  }

  private def readAs[T](rdr: Reader, mapper: java.util.Map[String, String] => T, filterPredicate: T => Boolean)(
    implicit ct: ClassTag[T]
  ): (Iterator[T], Closeable) = {
    val csvRdr = new CsvMapReader(rdr, CsvPreference.STANDARD_PREFERENCE)
    val header = csvRdr.getHeader(true)
    (Iterator.continually(csvRdr.read(header: _*)).takeWhile(_ != null).map(mapper).filter(filterPredicate), csvRdr)
  }

  def fromXmlFile(filePath: String): IndexedSeq[Event] = {
    val eventsManager = EventsUtils.createEventsManager()
    val events = new ArrayBuffer[Event]
    eventsManager.addHandler(new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        events += event
      }
    })
    fromFile(filePath, eventsManager)
    events
  }

  def fromFile(filePath: String, eventsManager: EventsManager): Unit = {
    val tempEventManager = EventsUtils.createEventsManager()
    tempEventManager.addHandler(new BasicEventHandler {
      def handleEvent(event: Event): Unit = {
        val fixedEvent = event match {
          case genericEvent: GenericEvent =>
            fixEvent(genericEvent)
          case _ => event
        }
        eventsManager.processEvent(fixedEvent)
      }
    })
    new MatsimEventsReader(tempEventManager).readFile(filePath)
  }

  def getEventsFilePath(matsimConfig: Config, name: String, extension: String, iteration: Int = 0): File = {
    new File(
      s"${matsimConfig.controler().getOutputDirectory}/ITERS/it.$iteration/$iteration.$name.$extension"
    )
  }

  def fixEvent(event: GenericEvent): Event = {
    event.getEventType match {
      case PathTraversalEvent.EVENT_TYPE =>
        PathTraversalEvent(event)
      case LeavingParkingEvent.EVENT_TYPE =>
        LeavingParkingEvent(event)
      case ParkingEvent.EVENT_TYPE =>
        ParkingEvent(event)
      case ModeChoiceEvent.EVENT_TYPE =>
        ModeChoiceEvent.apply(event)
      case PersonCostEvent.EVENT_TYPE =>
        PersonCostEvent.apply(event)
      case ReserveRideHailEvent.EVENT_TYPE =>
        ReserveRideHailEvent.apply(event)
      case AgencyRevenueEvent.EVENT_TYPE =>
        AgencyRevenueEvent.apply(event)
      case ReplanningEvent.EVENT_TYPE =>
        ReplanningEvent.apply(event)
      case _ =>
        event
    }
  }

}
