package beam.physsim.cchRoutingAssignment

import java.io.File
import java.nio.{ByteBuffer, ByteOrder}

import beam.utils.CloseableUtil._
import com.google.common.io.Files
import com.vividsolutions.jts.geom.Coordinate

import scala.collection.mutable

case class Vertex(id: Long, coordinate: Coordinate)
case class RoutingFrameworkGraph(vertices: Seq[Vertex])

/**
  * Reads graph in binary routing framework format
  */
trait RoutingFrameworkGraphReader {
  def read(graph: File): RoutingFrameworkGraph
}

class RoutingFrameworkGraphReaderImpl extends RoutingFrameworkGraphReader {
  private val coordinatePrecision = 1000000.0

  override def read(graph: File): RoutingFrameworkGraph =
    Files.asByteSource(graph).openStream().use { source =>
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

      def readInt(): Int = readLittleEndianBuffer(4).getInt

      def readLittleEndianBuffer(size: Int): ByteBuffer = {
        val bytes = new Array[Byte](size)
        source.read(bytes)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
      }

      val numOfVertices = readInt()
      val numOfEdges = readInt()

      (0 until numOfVertices).foreach(_ => readInt()) //outEdgesFirst

      (0 until numOfEdges).foreach(_ => readInt()) //outEdgesEnd

      val numOfAttributes = readInt()

      val vertexId2Coordinate = mutable.HashMap[Long, Coordinate]()
      (0 until numOfAttributes)
        .foreach { _ =>
          val attributeName = readString()
          val size = readInt()

          if (attributeName == "lat_lng") {
            readInt() // skip list size
            (0 until numOfVertices)
              .foreach(i =>
                vertexId2Coordinate
                  .put(i, new Coordinate(readInt() / coordinatePrecision, readInt() / coordinatePrecision))
              )
          } else {
            source.skip(size)
          }
        }

      val vertices = (0 until numOfVertices)
        .map { id =>
          vertexId2Coordinate(id) -> Vertex(id, vertexId2Coordinate(id))
        }
        // there might be duplicates by coordinate in vertices
        .toMap
        .values
        .toList

      RoutingFrameworkGraph(vertices)
    }
}

object GraphReaderApp extends App {
  val graphBinary = new File("/tmp/rt/beamville_graph.gr.bin")
  val reader: RoutingFrameworkGraphReader = new RoutingFrameworkGraphReaderImpl
  reader.read(graphBinary)
}
