package beam.utils.csv.conversion

import java.io.File

trait Xml2CsvFileConverter {
  protected def fields: Seq[String]
  protected def header: Iterator[String] = Iterator(fields.mkString(FieldSeparator), LineSeparator)

  protected val LineSeparator: String = "\n"
  protected val FieldSeparator: String = ","
  protected val ArrayFieldStartDelimiter = "["
  protected val ArrayFieldFinishDelimiter = "]"
  protected val ArrayElementsDelimiter = ":"

  def contentIterator(sourceFile: File): Iterator[String]

  def toCsv(sourceFile: File): Iterator[String] = header ++ contentIterator(sourceFile)

}
