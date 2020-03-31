package beam.physsim.routingTool
import java.io.File
import java.nio.{ByteBuffer, ByteOrder}

import com.google.common.io.Files

import scala.collection.mutable

case class LatLong(lat: Double, lon: Double)
case class Vertex(id: Long, latLong: LatLong)
case class RoutingToolGraph(vertexes: Seq[Vertex])

trait RoutingToolsGraphReader {
  def read(graph: File): RoutingToolGraph
}

object RoutingToolsGraphReaderImpl extends RoutingToolsGraphReader {
  override def read(graph: File): RoutingToolGraph = {

    val source = Files.asByteSource(graph).openStream()

    def readString(): String = {
      val b = mutable.ArrayBuffer[Byte]()
      Stream
        .continually(source.read())
        .takeWhile(_ != -1)
        .map(_.toByte)
        .takeWhile(_ != '\u0000')
        .foreach(b += _)
      new String(b.toArray)
    }

    def readInt(): Int = {
      readLittleEndianBuffer(4).getInt
    }

    def readLittleEndianBuffer(size: Int): ByteBuffer = {
      val bytes = new Array[Byte](size)
      source.read(bytes)
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    val vertices = readInt()
    val edges = readInt()

    val outEdgesFirst = (0 until vertices).map(_ => readInt())

    val outEdgesEnd = (0 until edges).map(_ => readInt())

    val numOfAttributes = readInt()

    val vertexId2Attribute = (0 until numOfAttributes)
      .flatMap { _ =>
        val attributeName = readString()
        val size = readInt()

        if (attributeName == "lat_lng") {
          readInt() // skip list size
          (0 until vertices)
            .map(i => i -> LatLong(readInt() / 1000000.0, readInt() / 1000000.0))
        } else {
          source.skip(size)
          Nil
        }
      }
      .groupBy { case (vertexId, _) => vertexId }
      .mapValues(_.map { case (_, attribute) => attribute })

    val vertexes = vertexId2Attribute.map {
      case (vertexId, attributes) =>
        val latLong: LatLong = attributes
          .find(_.isInstanceOf[LatLong])
          .getOrElse(throw new RuntimeException("lat_lng attribute not found in graph"))

        Vertex(vertexId, latLong)
    }.toSeq

    source.close()

    RoutingToolGraph(vertexes)
  }
}

object GraphReaderApp extends App {
  val graphBinary = new File("/tmp/rt/beamville_graph.gr.bin")
  val reader: RoutingToolsGraphReader = RoutingToolsGraphReaderImpl
  reader.read(graphBinary)
}
