package scripts
import java.io.File

trait XmlFileConverter {
  def toCsv(sourceFile: File, destinationFile: File): Iterator[String]
}
