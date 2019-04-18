package scripts
import java.io.File

trait XmlFileConverter {
  protected val LineSeparator: String = "\n"
  protected val FieldSeparator: String = ","
  protected val ArrayFieldStartDelimiter = "["
  protected val ArrayFieldFinishDelimiter = "]"
  protected val ArrayElementsDelimiter = ":"

  def toCsv(sourceFile: File, destinationFile: File): Iterator[String]
}
