package scripts
import java.io.File

import scripts.HouseHoldsConverter.{contentIterator, header}

trait XmlFileConverter {
  protected def fields: Seq[String]
  protected lazy val header: Iterator[String] = Iterator(fields.mkString(FieldSeparator), LineSeparator)

  protected val LineSeparator: String = "\n"
  protected val FieldSeparator: String = ","
  protected val ArrayFieldStartDelimiter = "["
  protected val ArrayFieldFinishDelimiter = "]"
  protected val ArrayElementsDelimiter = ":"

  def contentIterator(sourceFile: File): Iterator[String]

  def toCsv(sourceFile: File): Iterator[String] = header ++ contentIterator(sourceFile)

}
