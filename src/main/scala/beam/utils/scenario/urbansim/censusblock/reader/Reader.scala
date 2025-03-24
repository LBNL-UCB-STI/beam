package beam.utils.scenario.urbansim.censusblock.reader

trait Reader[T] extends AutoCloseable {
  def iterator(): Iterator[T]
}

trait ReaderFactory[T] {
  def createCsvReader(path: String): Reader[T]
  def createParquetReader(path: String): Reader[T]

  def createReader(path: String, fileFormat: String): Reader[T] = {
    fileFormat.toLowerCase match {
      case "csv" => createCsvReader(path)
      case "parquet" => createParquetReader(path)
      case _ => throw new IllegalArgumentException(s"Unsupported file format: $fileFormat")
    }
  }
}
