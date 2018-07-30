package beam.integration

import java.io.File

import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

import scala.collection.JavaConverters._
import scala.collection.immutable.Queue
import scala.io.Source

class ReadEventsBeam extends ReadEvents {
  val basicEventHandler = new BasicEventHandler {
    var events: Queue[Event] = Queue()
    def handleEvent(event: Event): Unit = {
      events = events :+ event
    }

  }

  def getListTagsFromFile(
      file: File,
      mkeyValue: Option[(String, String)] = None,
      tagToReturn: String,
      eventType: Option[String] = None,
      tagTwoToReturn: Option[String] = None): Seq[String] = {
    getListTagsFrom(file.getAbsolutePath, mkeyValue, tagToReturn, eventType)
  }

  def getListTwoTagsFromFile(
      file: File,
      mkeyValue: Option[(String, String)] = None,
      tagToReturn: String,
      eventType: Option[String] = None,
      tagTwoToReturn: Option[String] = None): Seq[(String, String)] = {
    getListTwoTagsFrom(file.getAbsolutePath,
                       mkeyValue,
                       tagToReturn,
                       eventType,
                       tagTwoToReturn.get)
  }

  def getListTagsFrom(filePath: String,
                      mkeyValue: Option[(String, String)] = None,
                      tagToReturn: String,
                      eventType: Option[String] = None): Seq[String] = {
    val eventsMan = EventsUtils.createEventsManager()
    eventsMan.addHandler(basicEventHandler)

    val reader = new MatsimEventsReader(eventsMan)
    reader.readFile(filePath)

    val events = basicEventHandler.events
    val filteredEvents = events.filter { event =>
      val attributes = event.getAttributes.asScala
      eventType.forall(_.equals(event.getEventType)) &&
      mkeyValue.forall {
        case (key, value) => attributes.get(key).exists(_.contains(value))
      }

    }
    filteredEvents
      .map(_.getAttributes.asScala.get(tagToReturn))
      .filter(_.isDefined)
      .map(_.get)

  }

  def getListTwoTagsFrom(filePath: String,
                         mkeyValue: Option[(String, String)] = None,
                         tagToReturn: String,
                         eventType: Option[String] = None,
                         tagTwoToReturn: String): Seq[(String, String)] = {
    val eventsMan = EventsUtils.createEventsManager()
    eventsMan.addHandler(basicEventHandler)

    val reader = new MatsimEventsReader(eventsMan)
    reader.readFile(filePath)

    val events = basicEventHandler.events
    val filteredEvents = events.filter { event =>
      val attributes = event.getAttributes.asScala
      eventType.forall(_.equals(event.getEventType)) &&
      mkeyValue.forall {
        case (key, value) => attributes.get(key).exists(_.contains(value))
      }

    }
    filteredEvents.map(f => {
      val attributes = f.getAttributes
      (attributes.asScala(tagToReturn), attributes.asScala(tagTwoToReturn))
    })
  }

  def getLinesFrom(file: File): String = {
    Source.fromFile(file.getPath).getLines.mkString
  }
}
