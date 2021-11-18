package beam.physsim.jdeqsim

import java.util

import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.network.Link
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.controler.OutputDirectoryHierarchy.OverwriteFileSetting
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2

import scala.collection.mutable
import scala.util.Try

class EventToHourFrequency(val controlerIO: OutputDirectoryHierarchy)
    extends BasicEventHandler
    with IterationStartsListener
    with IterationEndsListener
    with StrictLogging {
  private val eventToHourFreq: mutable.Map[String, mutable.Map[Int, Int]] = mutable.Map[String, mutable.Map[Int, Int]]()
  private var cnt: Int = 0

  override def handleEvent(event: Event): Unit = {
    cnt += 1
    val hour = (event.getTime / 3600).toInt
    val className = event.getClass.getSimpleName
    val hourFreq = eventToHourFreq.getOrElseUpdate(className, mutable.Map[Int, Int]())
    val prev = hourFreq.getOrElse(hour, 0)
    hourFreq.update(hour, prev + 1)
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    cnt = 0
    eventToHourFreq.clear()
  }

  override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
    val filePath = controlerIO.getIterationFilename(event.getIteration, "PhysSimEventToHourFrequency.csv")
    val csvWriter = new CsvWriter(filePath, Vector("event_type", "hour", "count"))
    val maxHour = eventToHourFreq.values.flatten.map(_._1).max
    logger.info(s"Handled $cnt events. MaxHour: $maxHour")

    try {
      (0 to maxHour).map { hour =>
        eventToHourFreq.map { case (event, hourFreq) =>
          val cnt = hourFreq.getOrElse(hour, 0)
          csvWriter.write(event, hour, cnt)
        }
      }
    } finally {
      Try(csvWriter.close())
    }
  }
}

object EventToHourFrequency extends StrictLogging {

  def main(args: Array[String]): Unit = {
    val pathToEventXml = args(0)

    val eventsManager = EventsUtils.createEventsManager()

    val eventHandler = new EventToHourFrequency(
      new OutputDirectoryHierarchy("", "", OverwriteFileSetting.failIfDirectoryExists, false)
    )
    eventsManager.addHandler(eventHandler)
    new MatsimEventsReader(eventsManager).readFile(pathToEventXml)

    eventHandler.notifyIterationEnds(new IterationEndsEvent(null, 0))
  }

  def initializeNetworkLinks(networkXml: String): util.Map[Id[Link], _ <: Link] = {
    val network = NetworkUtils.createNetwork
    val reader = new NetworkReaderMatsimV2(network)
    reader.readFile(networkXml)
    network.getLinks
  }
}
