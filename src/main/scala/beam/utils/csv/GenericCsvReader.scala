package beam.utils.csv

import java.io.Closeable

import beam.utils.FileUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag

trait GenericCsvReader {

  def readAs[T](
    pathOrUrl: String,
    mapper: java.util.Map[String, String] => T,
    filterPredicate: T => Boolean,
    preference: CsvPreference = CsvPreference.STANDARD_PREFERENCE
  )(
    implicit ct: ClassTag[T]
  ): (Iterator[T], Closeable) = {
    val csvRdr = new CsvMapReader(FileUtils.getReader(pathOrUrl), preference)
    read[T](csvRdr, mapper, filterPredicate)
  }

  def readFromStreamAs[T](
    stream: java.io.InputStream,
    mapper: java.util.Map[String, String] => T,
    filterPredicate: T => Boolean,
    preference: CsvPreference = CsvPreference.STANDARD_PREFERENCE
  )(
    implicit ct: ClassTag[T]
  ): (Iterator[T], Closeable) = {
    val csvRdr = new CsvMapReader(FileUtils.readerFromStream(stream), preference)
    read[T](csvRdr, mapper, filterPredicate)
  }

  def read[T](csvRdr: CsvMapReader, mapper: java.util.Map[String, String] => T, filterPredicate: T => Boolean)(
    implicit ct: ClassTag[T]
  ): (Iterator[T], Closeable) = {
    val header = csvRdr.getHeader(true)
    val iterator = Iterator
      .continually(csvRdr.read(header: _*))
      .takeWhile(_ != null)
      .map(mapper)
      .filter(filterPredicate)
    (iterator, csvRdr)
  }

  def getIfNotNull(rec: java.util.Map[String, String], column: String): String = {
    val v = rec.get(column)
    assert(v != null, s"Value in column '$column' is null")
    v
  }
}

object GenericCsvReader extends GenericCsvReader
