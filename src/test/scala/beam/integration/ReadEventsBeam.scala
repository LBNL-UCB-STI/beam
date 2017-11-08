package beam.integration

import java.io.File

import org.matsim.api.core.v01.events.Event
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.events.{EventsUtils, MatsimEventsReader}

import scala.io.Source
import scala.collection.JavaConverters._

class ReadEventsBeam extends ReadEvents{
  val basicEventHandler = new BasicEventHandler{
    var events: Seq[Event] = Seq()
    def handleEvent(event: Event): Unit = {
      events = events :+ event
    }
    def reset(iteration: Int): Unit = {
    }
  }

  def getListTagsFromFile(file: File, mkeyValue: Option[(String, String)] = None,
                          tagToReturn: String,
                          eventType: Option[String] = None): Seq[String] = {
    getListTagsFrom(file.getAbsolutePath, mkeyValue, tagToReturn, eventType)
  }

  def getListTagsFrom(filePath: String, mkeyValue: Option[(String, String)] = None,
                      tagToReturn: String,
                      eventType: Option[String] = None): Seq[String] = {
    val eventsMan = EventsUtils.createEventsManager()
    eventsMan.addHandler(basicEventHandler)

    val reader = new MatsimEventsReader(eventsMan)
    reader.readFile(filePath)

    val events = basicEventHandler.events
    val filteredEvents = events.filter{ event =>
      val attributes = event.getAttributes.asScala
      eventType.map(_.equals(event.getEventType)).getOrElse(true) &&
        mkeyValue.map{case (key, value) => attributes.get(key).filter(_.contains(value)).isDefined}.getOrElse(true)

    }
    filteredEvents
      .map(_.getAttributes.asScala.get(tagToReturn))
      .filter(_.isDefined)
      .map(_.get)

  }

  def getLinesFrom(file: File): String = {
    Source.fromFile(file.getPath).getLines.mkString
  }
}
