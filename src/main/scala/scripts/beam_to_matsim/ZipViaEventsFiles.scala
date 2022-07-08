package scripts.beam_to_matsim

import beam.utils.FileUtils

import java.io.{File, PrintWriter}
import scala.language.postfixOps
import scala.xml.{Node, XML}

/*
a script to merge two files from EventsByVehicleMode script output to get one file containings all events
 */

object ZipViaEventsFiles extends App {

  // format: off
  /****************************************************************************************************
    ./gradlew execute -PmainClass=beam.utils.beam_to_matsim.scripts.via.ZipViaEventsFiles -PappArgs="[
      '<via events xml file 1>',
      '<via events xml file 2>',
      '<via events output xml file>',
    ]" -PmaxRAM=16g
  *****************************************************************************************************/
  // format: on

  val viaEvents1 = args(0)
  val viaEvents2 = args(1)
  val outputFile = args(2)

  case class ViaEventString(time: Double, xmlString: String)

  object ViaEventString {

    def readXml(xmlevent: Node): Option[ViaEventString] = {
      val timeOption =
        try {
          xmlevent.attribute("time") match {
            case Some(attVal) => Some(attVal.text.toDouble)
            case _            => None
          }
        } catch {
          case _: Exception => None
        }

      timeOption match {
        case Some(time) => Some(ViaEventString(time, xmlevent.toString))
        case _          => None
      }
    }

    def readStr(str: String): Option[ViaEventString] = {
      val timeOption =
        try {
          XML.loadString(str).attribute("time") match {
            case Some(attVal) => Some(attVal.text.toDouble)
            case _            => None
          }
        } catch {
          case _: Exception => None
        }

      timeOption match {
        case Some(time) => Some(ViaEventString(time, str))
        case _          => None
      }
    }
  }

  def zipEventsFilesInMemory(filePath1: String, filePath2: String, outputFile: String): Unit = {
    object EventsIterator {
      def fromFile(filePath: String): EventsIterator = {
        val xmlEvents = XML.loadFile(filePath) \ "event"
        Console.println("events read")
        val parsedEvents = xmlEvents.flatMap(xmlEvent => ViaEventString.readXml(xmlEvent))
        Console.println("events parsed")
        val iterator = EventsIterator(parsedEvents.toArray)

        Console.println("created iterator from " + filePath)
        iterator
      }
    }

    case class EventsIterator(events: Array[ViaEventString]) extends Iterator[ViaEventString] {
      private var current = 0

      override def hasNext: Boolean = current < events.length

      override def next(): ViaEventString = {
        current += 1
        events(current - 1)
      }
    }

    FileUtils.using(new PrintWriter(new File(outputFile))) { pw =>
      pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")

      def writeEvent(event: ViaEventString): Unit = pw.println(event.xmlString)

      val events1 = EventsIterator.fromFile(filePath1)
      val events2 = EventsIterator.fromFile(filePath2)

      if (events1.hasNext && events2.hasNext) {
        var e1 = events1.next()
        var e2 = events2.next()

        while (events1.hasNext && events2.hasNext) {

          if (e1.time <= e2.time) {
            writeEvent(e1)
            e1 = events1.next()
          } else {
            writeEvent(e2)
            e2 = events2.next()
          }
        }
      }

      Console.println("--> joined into " + outputFile)

      def writeTail(eventsIterator: EventsIterator): Unit = eventsIterator.foreach(writeEvent)
      //while (eventsIterator.hasNext) writeEvent(eventsIterator.next())

      if (events1.hasNext) writeTail(events1)
      if (events2.hasNext) writeTail(events2)

      pw.println("</events>")
    }
  }

  zipEventsFilesInMemory(viaEvents1, viaEvents2, outputFile)
}
