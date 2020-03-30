package beam.physsim.routingTool
import java.io.{DataInputStream, File}
import java.nio.ByteBuffer

import com.google.common.io.Files

object GraphReader extends App{
  val graphBinary = new File("/tmp/rt/beamville_graph.gr.bin")

  val source = Files.asByteSource(graphBinary)



  val stream = new DataInputStream(source.openStream())

  print(stream.readInt())
  print(1)

}
