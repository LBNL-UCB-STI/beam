package beam.router

import java.io.{File, FileInputStream, FileOutputStream}
import java.nio.charset.StandardCharsets
import java.nio.file.{Files, Path, Paths}
import java.time.{LocalDateTime, ZoneOffset}
import java.time.format.DateTimeFormatter
import javax.xml.namespace.QName
import javax.xml.stream.{XMLInputFactory, XMLOutputFactory, XMLStreamWriter}
import javax.xml.stream.events.{Attribute, Characters, StartElement, XMLEvent}

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.{Success, Try}

import beam.agentsim.infrastructure.geozone.WgsCoordinate
import beam.router.Modes.BeamMode
import com.typesafe.scalalogging.StrictLogging
import scopt.OParser

object PhyssymXmlToOsmConverter extends StrictLogging {

  def build(xmlSourceFile: Path): OsmNetwork = {
    require(Files.isRegularFile(xmlSourceFile), s"Path [$xmlSourceFile] is not regular file")

    var nodes: mutable.Map[String, Node] = null
    var canProcessNode = false

    var links: mutable.ArrayBuffer[Way] = null
    var canProcessLink = false
    var canProcessLinkAttributes = false
    var canProcessAttribute = false
    var isWayId = false
    var isHighway = false

    var wayId: String = null
    var wayInnerTags: mutable.Buffer[(String, String)] = null
    var source_destination: (String, String) = null

    val xmlInputFactory: XMLInputFactory = XMLInputFactory.newInstance
    logger.info(s"Reading from file [$xmlSourceFile]")
    val reader = xmlInputFactory.createXMLEventReader(new FileInputStream(xmlSourceFile.toFile))
    while (reader.hasNext) {
      val nextEvent: XMLEvent = reader.nextEvent
      if (nextEvent.isStartElement) {
        val startElement: StartElement = nextEvent.asStartElement
        val localPart = startElement.getName.getLocalPart
        localPart match {
          case "network" =>
          case "nodes" =>
            nodes = mutable.Map.empty[String, Node]
            canProcessNode = true
          case "node" if canProcessNode =>
            val id = startElement.getAttributeByName(new QName("id"))
            nodes += id.getValue -> buildNode(startElement, id)
          case "links" if !canProcessLink =>
            links = mutable.ArrayBuffer.empty[Way]
            canProcessLink = true
          case "link" if canProcessLink =>
            source_destination = (
              startElement.getAttributeByName(new QName("from")).getValue,
              startElement.getAttributeByName(new QName("to")).getValue
            )
            wayInnerTags = buildLinkProps(startElement)
          case "attributes" if canProcessLink =>
            canProcessLinkAttributes = true
          case "attribute" if canProcessLinkAttributes && tagNameHasKind(startElement, "origid") =>
            canProcessAttribute = true
            isWayId = true
            isHighway = false
          case "attribute" if canProcessLinkAttributes && tagNameHasKind(startElement, "type") =>
            isWayId = false
            isHighway = true
          case somethingElse =>
            logger.warn(s"Unidentified tag: [$somethingElse]")
        }
      } else if (nextEvent.isEndElement) {
        val endElement = nextEvent.asEndElement()
        endElement.getName.getLocalPart match {
          case "nodes" => canProcessNode = false
          case "link" if canProcessLink =>
            links += Way(wayId, source_destination._1, source_destination._2, wayInnerTags)
          case "links" if canProcessLink =>
            canProcessLink = false
          case "attributes" if canProcessLinkAttributes => canProcessLinkAttributes = false
          case "attribute" if canProcessLinkAttributes && isWayId =>
            isWayId = false
          case "attribute" if canProcessLinkAttributes && isHighway =>
            isHighway = false
          case somethingElse =>
            logger.warn(s"Something not predicted $somethingElse")
        }
      } else if (nextEvent.isCharacters) {
        val characters: Characters = nextEvent.asCharacters()
        val charactersValue = characters.getData
        if (!isBlank(charactersValue)) {
          if (isWayId) {
            wayId = charactersValue
          } else if (isHighway) {
            wayInnerTags += "highway" -> charactersValue
          }
        }
      }
    }
    logger.info("Network loaded")
    new OsmNetwork(nodes.values.toSeq, links)
  }

  private def isBlank(str: String): Boolean = {
    val allBlank = Seq(' ', '\t', '\r', '\n')
    str == null || str.forall(c => allBlank.contains(c))
  }

  private def buildLinkProps(startElement: StartElement): mutable.Buffer[(String, String)] = {
    val freeSpeedy = startElement.getAttributeByName(new QName("freespeed"))
    val capacity = startElement.getAttributeByName(new QName("capacity"))
    val permLanes = startElement.getAttributeByName(new QName("permlanes"))
    val oneway = startElement.getAttributeByName(new QName("oneway"))

    val modes = startElement.getAttributeByName(new QName("modes")).getValue

    val result: Seq[(String, String)] = buildSequenceForNonNullValues(
      attributes =
      "lanes"    -> permLanes.getValue,
      "oneway"   -> (if (Seq("1", "true").contains(oneway.getValue.toLowerCase)) "yes" else "no"),
      "capacity" -> capacity.getValue,
      "maxspeed" -> freeSpeedy.getValue
    ) ++ toMultipleInnerTags(modes)
    new ArrayBuffer() ++ result
  }

  private def toMultipleInnerTags(modes: String): Seq[(String, String)] = {
    modes
      .split(",")
      .map(_.trim)
      .flatMap {
        case BeamMode.CAR.value    => Some("motorcar" -> "yes")
        case BeamMode.BUS.value    => Some("bus"      -> "yes")
        case BeamMode.RAIL.value   => Some("rail"  -> "yes")
        case BeamMode.SUBWAY.value => Some("subway"   -> "yes")
        case BeamMode.TRAM.value   => Some("tram"     -> "yes")
        case BeamMode.BIKE.value   => Some("bicycle"  -> "yes")
        case somethingElse: String =>
          logger.warn(s"$somethingElse is handled but is not tested")
          Some(somethingElse -> "yes")
      }
      .toSeq
  }

  private def buildNode(
    startElement: StartElement,
    id: Attribute
  ): Node = {
    val x = startElement.getAttributeByName(new QName("x"))
    val y = startElement.getAttributeByName(new QName("y"))
    val wgsCoordinate = WgsCoordinate.fromUtm(
      longitude = x.getValue.toDouble,
      latitude = y.getValue.toDouble,
    )
    Node(id.getValue, wgsCoordinate)
  }

  private def buildSequenceForNonNullValues(attributes: (String, String)*): Seq[(String, String)] = {
    val result: Seq[(String, String)] = attributes.flatMap { attr =>
      Option(attr._2).map { value =>
        attr._1 -> value
      }
    }
    Seq(result: _*)
  }

  private def tagNameHasKind(startElement: StartElement, kind: String): Boolean = {
    val elementKind = startElement.getAttributeByName(new QName("name"))
    elementKind != null && elementKind.getValue.equals(kind)
  }

  case class Node(id: String, wgsCoordinate: WgsCoordinate)
  case class Way(id: String, from: String, to: String, attributes: Seq[(String, String)])

  class OsmNetwork(nodes: Seq[Node], ways: Seq[Way]) {
    private val breakLine = "\n"
    private val indentation = "  "
    private val zoned = LocalDateTime.now.atZone(ZoneOffset.UTC)
    private val timeStampStr = DateTimeFormatter.ISO_INSTANT.format(zoned)

    def writeToFile(path: Path): Unit = {
      logger.info(s"Writing conversion in the file [$path]")
      val xmlOutputFactory = XMLOutputFactory.newInstance

      val xmlStreamWriter: XMLStreamWriter = xmlOutputFactory.createXMLStreamWriter(new FileOutputStream(path.toFile))
      xmlStreamWriter.writeStartDocument(StandardCharsets.UTF_8.name(), "1.0")
      xmlStreamWriter.writeCharacters(breakLine)
      xmlStreamWriter.writeStartElement("osm")
      xmlStreamWriter.writeAttribute("version", "0.6")
      xmlStreamWriter.writeAttribute("generator", "beam")
      xmlStreamWriter.writeCharacters(breakLine)

      writeXmlNodes(xmlStreamWriter, nodes)
      ways.foreach{ way =>
        xmlStreamWriter.writeCharacters(indentation)
        xmlStreamWriter.writeStartElement("way")

        xmlStreamWriter.writeAttribute("id", way.id)
        xmlStreamWriter.writeAttribute("version", "1")
        xmlStreamWriter.writeAttribute("timestamp", timeStampStr)
        xmlStreamWriter.writeCharacters(breakLine)

        writeWayTags(xmlStreamWriter, way)

        xmlStreamWriter.writeCharacters(indentation)
        xmlStreamWriter.writeEndElement()
        xmlStreamWriter.writeCharacters(breakLine)
      }
      xmlStreamWriter.writeEndDocument()
      logger.info(s"Finished conversion. File: [$path]")
    }

    private def writeWayTags(xmlStreamWriter: XMLStreamWriter, way: Way): Unit = {
      writeNd(xmlStreamWriter, way.from)
      writeNd(xmlStreamWriter, way.to)

      way.attributes.foreach {
        case (prop, value) =>
          xmlStreamWriter.writeCharacters(indentation * 2)

          xmlStreamWriter.writeEmptyElement("tag")
          xmlStreamWriter.writeAttribute("k", prop)
          xmlStreamWriter.writeAttribute("v", value)

          xmlStreamWriter.writeCharacters(breakLine)
      }
    }

    private def writeNd(xmlStreamWriter: XMLStreamWriter, value: String): Unit = {
      xmlStreamWriter.writeCharacters(indentation * 2)

      xmlStreamWriter.writeEmptyElement("nd")
      xmlStreamWriter.writeAttribute("ref", value)

      xmlStreamWriter.writeCharacters(breakLine)

    }

    private def writeXmlNodes(xmlStreamWriter: XMLStreamWriter, nodes: Seq[Node]): Unit = {
      nodes.foreach { node =>
        xmlStreamWriter.writeCharacters(indentation)

        xmlStreamWriter.writeEmptyElement("node")

        xmlStreamWriter.writeAttribute("id", node.id)

        xmlStreamWriter.writeAttribute("version", "1")
        xmlStreamWriter.writeAttribute("timestamp", timeStampStr)

        xmlStreamWriter.writeAttribute("lat", node.wgsCoordinate.latitude.toString)
        xmlStreamWriter.writeAttribute("lon", node.wgsCoordinate.longitude.toString)

        xmlStreamWriter.writeCharacters(breakLine)
      }
    }
  }

  def main(args: Array[String]): Unit = {
    PhyssymXmlToOsmConverterParams.tryReadParams(args) match {
      case Success(params)  =>
        println(s"Started converting ${params.sourceFile} to ${params.targetFile}...")
        val network = build(params.sourceFile.toPath)
        network.writeToFile(params.targetFile.toPath)
        println(s"Finished converting ${params.sourceFile} to ${params.targetFile}")
      case _ =>
        System.exit(1)
    }
  }

}


private object PhyssymXmlToOsmConverterParams {

  case class ConverterParams(sourceFile: File = null, targetFile: File = null)

  private val builder = OParser.builder[ConverterParams]
  private val parser1 = {
    import builder._
    OParser.sequence(
      programName("BeamPhyssymConverter"),
      head("BeamPhyssymConverter", "0.1"),
      opt[File](name="sourceFile")
        .action((x, c) => c.copy(sourceFile = x))
        .text("sourceFile is a valid Physym network file")
        .required()
        .validate { v =>
          if (v.isFile) success
          else failure(s"sourceFile [$v] is not a regular file")
        },
      opt[File](name="targetFile")
        .action((x, c) => c.copy(targetFile = x))
        .text("targetFile is a valid path for the output OSM file")
        .required(),
      checkConfig{c =>
        if (c.sourceFile == c.targetFile) {
          failure("sourceFile cannot be the same as targetFile")
        } else {
          success
        }
      }
    )
  }

  def tryReadParams(args: Array[String]): Try[ConverterParams] = {
    OParser.parse(parser1, args, ConverterParams())
      .toRight(new IllegalArgumentException("Invalid arguments"))
      .toTry
  }

}
