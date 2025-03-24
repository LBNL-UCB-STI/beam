package beam.utils.scenario.urbansim.censusblock.reader

import beam.utils.scenario.urbansim.censusblock.entities._

object ReaderFactories {
  implicit val personReaderFactory: ReaderFactory[InputPersonInfo] = new ReaderFactory[InputPersonInfo] {
    override def createCsvReader(path: String): Reader[InputPersonInfo] = new CsvPersonReader(path)
    override def createParquetReader(path: String): Reader[InputPersonInfo] = new ParquetPersonReader(path)
  }

  implicit val planReaderFactory: ReaderFactory[InputPlanElement] = new ReaderFactory[InputPlanElement] {
    override def createCsvReader(path: String): Reader[InputPlanElement] = new CsvPlanReader(path)
    override def createParquetReader(path: String): Reader[InputPlanElement] = new ParquetPlanReader(path)
  }

  implicit val householdReaderFactory: ReaderFactory[InputHousehold] = new ReaderFactory[InputHousehold] {
    override def createCsvReader(path: String): Reader[InputHousehold] = new CsvHouseholdReader(path)
    override def createParquetReader(path: String): Reader[InputHousehold] = new ParquetHouseholdReader(path)
  }

  implicit val blockReaderFactory: ReaderFactory[Block] = new ReaderFactory[Block] {
    override def createCsvReader(path: String): Reader[Block] = new CsvBlockReader(path)
    override def createParquetReader(path: String): Reader[Block] = new ParquetBlockReader(path)
  }
} 