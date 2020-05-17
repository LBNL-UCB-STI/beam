package beam.physsim.cch
import java.io.File
import java.nio.{ByteBuffer, ByteOrder}

import com.google.common.io.Files
import com.vividsolutions.jts.geom.Coordinate

import scala.collection.mutable

case class Vertex(id: Long, coordinate: Coordinate)
case class RoutingFrameworkGraph(vertices: Seq[Vertex])

trait RoutingFrameworkGraphReader {
  def read(graph: File): RoutingFrameworkGraph
}

class RoutingFrameworkGraphReaderImpl extends RoutingFrameworkGraphReader {
  override def read(graph: File): RoutingFrameworkGraph = {

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

    val numOfVertices = readInt()
    val numOfEdges = readInt()

    val outEdgesFirst = (0 until numOfVertices).map(_ => readInt())

    val outEdgesEnd = (0 until numOfEdges).map(_ => readInt())

    val numOfAttributes = readInt()

    val vertexId2Coordinate = mutable.HashMap[Long, Coordinate]()
    (0 until numOfAttributes)
      .foreach { _ =>
        val attributeName = readString()
        val size = readInt()

        if (attributeName == "lat_lng") {
          readInt() // skip list size
          (0 until numOfVertices)
            .foreach(i => vertexId2Coordinate.put(i, new Coordinate(readInt() / 1000000.0, readInt() / 1000000.0)))
        } else {
          source.skip(size)
        }
      }

    val vertices = (0 until numOfVertices).map { id =>
      vertexId2Coordinate(id) -> Vertex(id, vertexId2Coordinate(id))
    }
      // there might be duplicates by coordinate in vertices
      .toMap.values.toList

    source.close()

    RoutingFrameworkGraph(vertices)
  }
}

object GraphReaderApp extends App {
  val graphBinary = new File("/tmp/rt/beamville_graph.gr.bin")
  val reader: RoutingFrameworkGraphReader = new RoutingFrameworkGraphReaderImpl
  reader.read(graphBinary)
}
