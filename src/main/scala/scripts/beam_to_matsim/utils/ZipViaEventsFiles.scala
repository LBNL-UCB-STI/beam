package beam.utils.beam_to_matsim.utils

import java.io.{File, PrintWriter}

import beam.utils.FileUtils

import scala.collection.mutable
import scala.io.Source
import scala.language.postfixOps
import scala.xml.{Node, XML}

/*
a script to merge two files from EventsByVehicleMode script output to get one file containings all events
 */

object ZipViaEventsFiles extends App {
  /*zipEventsFilesInMemory(
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv.via.events.persons_1_2.xml",
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv.via.events.person3.xml",
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv.via.events.persons_1_2_3.xml"
  )*/

  zipEventsFilesInMemory(
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv.via.events.person5_1.xml",
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv.via.events.popSize0.3.xml",
    "D:/Work/BEAM/visualizations/v2.it20.events.bridge_cap_5000.half.csv.via.events.ZIPPED_p5.xml"
  )

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

  case class ViaEventString(time: Double, xmlString: String)

  def zipEventsFiles(filePath1: String, filePath2: String, outputFile: String): Unit = {
    FileUtils.using(new PrintWriter(new File(outputFile))) { pw =>
      pw.println("<?xml version=\"1.0\" encoding=\"utf-8\"?>\n<events version=\"1.0\">")

      val pwCashe = mutable.Queue.empty[String]

      def writeEvent(event: ViaEventString): Unit = {
        pwCashe.enqueue(event.xmlString)

        if (pwCashe.size >= 1000000) {
          pwCashe.dequeueAll(_ => true).foreach(pw.println)
          Console.println("dropped to output file portion of messages")
        }
      }

      def getReader(filePath: String): (Iterator[String], () => Unit) = {
        val source = Source fromFile filePath
        (source.getLines, () => source.close())
      }

      val (file1Iterator, file1Close) = getReader(filePath1)
      val (file2Iterator, file2Close) = getReader(filePath2)

      def nextEvent(iterator: Iterator[String]): Option[ViaEventString] =
        ViaEventString.readStr(if (iterator.hasNext) iterator.next() else "")

      def nextEvent1(): Option[ViaEventString] = nextEvent(file1Iterator)

      def nextEvent2(): Option[ViaEventString] = nextEvent(file2Iterator)

      var ev1 = nextEvent1()
      var ev2 = nextEvent2()

      while (file1Iterator.hasNext && file2Iterator.hasNext) {
        (ev1, ev2) match {
          case (None, _) => ev1 = nextEvent1()
          case (_, None) => ev2 = nextEvent2()

          case (Some(e1), Some(e2)) =>
            if (e1.time <= e2.time) {
              writeEvent(e1)
              ev1 = nextEvent1()
            } else {
              writeEvent(e2)
              ev2 = nextEvent2()
            }
        }
      }

      pwCashe.dequeueAll(_ => true).foreach(pw.println)

      def writeTail(iterator: Iterator[String]): Unit =
        iterator.foreach(event => {
          ViaEventString.readStr(event) match {
            case Some(viaES) => writeEvent(viaES)
            case _           =>
          }
        })

      if (file1Iterator.hasNext) writeTail(file1Iterator)
      if (file2Iterator.hasNext) writeTail(file2Iterator)

      pw.println("</events>")

      file1Close()
      file2Close()
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
}
