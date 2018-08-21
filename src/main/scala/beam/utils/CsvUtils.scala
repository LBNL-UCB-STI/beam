package beam.utils

import java.util
import java.util.stream.Collectors

import scala.reflect
import scala.reflect.ClassTag
import scala.util.Try

import org.matsim.core.utils.io.IOUtils

import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference
import beam.utils.CsvUtils._

import beam.utils.FileUtils.safeLines

class CsvUtils(pathName: String, headerLines: Int, srcFilter: Option[Fields => Boolean] = None) {

  /**
    * List of CSV files contained in a directory defined in the path if
    * it is a directory. The list contains the name of the path is
    * it is a file
    */
  lazy val filesList: Try[Array[String]] = Try {
    import java.io.File
    val file = new File(pathName)
    if (file.isDirectory)
      file.listFiles.map(_.getName)
    else
      Array[String](pathName)
  }
//  /**
//    * Load and convert the content of a file (in resources directory) into a list of fields. The fields
//    * are defined as a sequence of type T.
//    * @param c implicit conversion of a String to a type.
//    * @return List of sequence of fields of the extraction has been successful, None otherwise
//    */
//  def loadConvert[T: ClassTag](implicit c: String => T): Try[List[Array[T]]] = Try(
//    safeLines(getClass.getResource(pathName).getPath).map(_.split(CSV_DELIM).map{x:String=>c(x)})()
//  )

//  def loadCsvFile[T:ClassTag](implicit c: String=>T): Try[Array[T]] ={
//    val mapReader: ICsvMapReader = new CsvMapReader(readerFromFile(getClass.getResource(pathName).getPath), CsvPreference.STANDARD_PREFERENCE)
//    val header = mapReader.getHeader(true)
//    val line: java.util.Map[String,String] = mapReader.read(header:_*)
//    load.map{case(fields,_)=> fields.map(field=>c(line.get(field)))}
//  }
//
}

object CsvUtils {
  final val CSV_DELIM: String = ","
  final val REVERSE_ORDER: Boolean = true
  type Fields = Array[String]

  type U = List[Fields => Double]
  type V = Vector[Array[Double]]
  type PFSRC = PartialFunction[U, Try[V]]

  //TODO: get w/ safe resource release pattern: FileUtils.using
  def readerFromFile(filePath: String): java.io.BufferedReader = {
    IOUtils.getBufferedReader(filePath)
  }

  def getHash(concatParams: Any*): Int = {
    val concatString = concatParams.foldLeft("")((a, b) => a + b)
    concatString.hashCode
  }

}
