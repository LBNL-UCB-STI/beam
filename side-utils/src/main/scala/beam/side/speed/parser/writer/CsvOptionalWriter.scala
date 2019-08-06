package beam.side.speed.parser.writer

import java.io.{BufferedWriter, File, FileWriter}

import beam.side.speed.model.Encoder
import beam.side.speed.model.Encoder._
import scala.reflect.runtime.universe._
import beam.side.speed.parser.operation.SpeedWriter

class CsvOptionalWriter[T <: Product: TypeTag: Encoder](fileName: String) extends SpeedWriter[T, Option] {

  private val bw = new BufferedWriter(new FileWriter(new File(fileName)))
  bw.write(Encoder[T].header.mkString(","))

  override def write(data: T): Option[Unit] = {
    bw.newLine()
    Some(bw.write(data.row))
  }

  override def flush(): Option[Unit] = {
    bw.flush()
    Some(bw.close())
  }
}
