package beam.utils.csv

import java.io.Closeable

import beam.utils.FileUtils
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.reflect.ClassTag

trait GenericCsvReader {

  def readAs[T](
    path: String,
    mapper: java.util.Map[String, String] => T,
    filterPredicate: T => Boolean,
    preference: CsvPreference = CsvPreference.STANDARD_PREFERENCE
  )(implicit
    ct: ClassTag[T]
  ): (Iterator[T], Closeable) = {
    val csvRdr = new CsvMapReader(FileUtils.readerFromFile(path), preference)
    read[T](csvRdr, mapper, filterPredicate)
  }

  /**
    * This method should not be used on larger files as it loads the entire contents into memory.
    * Use readAs or another view-like method for larger files.
    */
  def readAsSeq[T](
    path: String,
    filterPredicate: T => Boolean = (_: T) => true,
    preference: CsvPreference = CsvPreference.STANDARD_PREFERENCE
  )(mapper: java.util.Map[String, String] => T)(implicit
    ct: ClassTag[T]
  ): IndexedSeq[T] = {
    val (iter: Iterator[T], toClose: Closeable) = GenericCsvReader.readAs[T](path, mapper, filterPredicate, preference)
    try {
      iter.toIndexedSeq
    } finally {
      toClose.close()
    }
  }

  def readFromStreamAs[T](
    stream: java.io.InputStream,
    mapper: java.util.Map[String, String] => T,
    filterPredicate: T => Boolean,
    preference: CsvPreference = CsvPreference.STANDARD_PREFERENCE
  )(implicit
    ct: ClassTag[T]
  ): (Iterator[T], Closeable) =
    readFromReaderAs(FileUtils.readerFromStream(stream), mapper, filterPredicate, preference)

  def readFromReaderAs[T](
    reader: java.io.Reader,
    mapper: java.util.Map[String, String] => T,
    filterPredicate: T => Boolean = (_: T) => true,
    preference: CsvPreference = CsvPreference.STANDARD_PREFERENCE
  )(implicit
    ct: ClassTag[T]
  ): (Iterator[T], Closeable) = {
    val csvRdr = new CsvMapReader(reader, preference)
    read[T](csvRdr, mapper, filterPredicate)
  }

  def read[T](csvRdr: CsvMapReader, mapper: java.util.Map[String, String] => T, filterPredicate: T => Boolean)(implicit
    ct: ClassTag[T]
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
