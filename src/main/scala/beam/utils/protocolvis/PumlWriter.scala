package beam.utils.protocolvis

import beam.utils.FileUtils

import java.nio.file.Path

/**
  * @author Dmitry Openkov
  */
object PumlWriter {

  def writeData[T](data: IndexedSeq[T], path: Path)(serializer: T => String): Unit = {
    val stringValues = "@startuml" +: data.map(serializer) :+ "@enduml"
    val withNewLines = stringValues.flatMap(Seq(_, "\n"))
    FileUtils.writeToFile(path.toString, withNewLines.iterator)
  }
}
