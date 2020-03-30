package beam.physsim.routingTool
import java.io.File
import java.nio.{ByteBuffer, ByteOrder}

import com.google.common.io.Files

import scala.collection.mutable

object GraphReader extends App {
  val graphBinary = new File("/tmp/rt/beamville_graph.gr.bin")

  val source = Files.asByteSource(graphBinary).openStream()

  val vertices = readInt()
  val edges = readInt()

  val outEdgesFirst = (0 until vertices).map(_ => readInt())

  val outEdgesEnd = (0 until edges).map(_ => readInt())

  val numOfAttributes = readInt()

  (0 until numOfAttributes).foreach { _ =>
    val attributeName = readString()
    val size = readInt()

    if (attributeName == "lat_lng") {
      readInt() // skip list size
      val index2LatLng = (0 until vertices)
        .map(i => i -> (readInt() / 1000000.0 -> readInt()/1000000.0))
      println()
    } else {
      source.skip(size)
    }
  }

  throw new RuntimeException("lat_lng attribute not found in graph")

  println()

  //------------------------
  private def readString(): String = {
    val b = mutable.ArrayBuffer[Byte]()
    Stream
      .continually(source.read())
      .takeWhile(_ != -1)
      .map(_.toByte)
      .takeWhile(_ != '\u0000')
      .foreach(b += _)
    new String(b.toArray)
  }

  private def readInt(): Int = {
    readLittleEndianBuffer(4).getInt
  }

  private def readLittleEndianBuffer(size: Int): ByteBuffer = {
    val bytes = new Array[Byte](size)
    source.read(bytes)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
  }
}
