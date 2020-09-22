package beam.utils

import java.io.BufferedReader
import java.nio.file.{Path, Paths}

import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.scalatest.{Matchers, WordSpecLike}
import scala.collection.JavaConverters._

/**
  *
  * @author Dmitry Openkov
  */
class FileUtilsSpec extends WordSpecLike with Matchers {

  val skimPath: Path = Paths.get(System.getenv("PWD"), "test/test-resources/beam/od-skims/multi-part-od-skims")

  "FileUtils" must {
    "read files in parallel into a map" in {
      val result = FileUtils.parRead(skimPath, "od_for_test_part*.csv.gz") { (path, reader) =>
        val numPattern = "\\d+".r
        val partNumber = numPattern.findFirstIn(path.getFileName.toString).get.toInt
        val records: scala.List[_root_.com.univocity.parsers.common.record.Record] = parseCSV(reader)
        partNumber -> records
      }
      result.size should be(3)
      result.keys should contain allOf (1, 2, 3)
      result(1).size should be(3)
      result(1).head.getInt("hour") should be(0)
      result(2).size should be(3)
      result(2).head.getInt("hour") should be(1)
      result(3).size should be(3)
      result(3).head.getInt("hour") should be(2)
    }

    "read files in parallel into a flat iterable" in {
      val result = FileUtils
        .flatParRead(skimPath, "od_for_test_part*.csv.gz") { (path, reader) =>
          val records: scala.List[_root_.com.univocity.parsers.common.record.Record] = parseCSV(reader)
          records
        }
        .toList
        .sortBy(_.getInt("hour"))
      result.map(_.getInt("hour")) should be((0 to 8).toList)
    }

    "write data to files in parallel" in {
      val data = Array.ofDim[String](111)
      for (i <- data.indices) {
        data(i) = i + (",CAR,101241,101241,153,153.0,0.128873295,0.468873295," +
        "1175.0,0.0,0,0,CAR,101241,101241,153,153.0,0.128873295,0.468873295,1175.0,0.0,0,0CAR,101241,101241,153," +
        "153.0,0.128873295,0.468873295,1175.0,0.0,0,0")
      }
      val numberOfParts = 4
      FileUtils.usingTemporaryDirectory { tmpDir =>
        FileUtils.parWrite(tmpDir, "part$i.csv.gz", numberOfParts) { (i, _, writer) =>
          val n = data.length / numberOfParts
          val from = (i - 1) * n
          val until = if (i < numberOfParts) from + n else data.length
          val mySlice = data.view(from, until)
          writer.write("a,b,c,d,e,f,g,h,i,j,k,l,m,n,o,p,q,r,s,t,u,x,y,z,a1,a2,a3,a4,a5,a6,a7,a8,a9")
          mySlice.foreach { x =>
            writer.newLine()
            writer.write(x)
          }
        }
        val records = FileUtils
          .flatParRead(tmpDir, "part*.csv.gz") { (path, reader) =>
            parseCSV(reader)
          }
          .toList
        records.size should be(111)
      }
    }
  }

  private def parseCSV(reader: BufferedReader) = {
    val settings = new CsvParserSettings()
    settings.setHeaderExtractionEnabled(true)
    settings.detectFormatAutomatically()
    val csvParser = new CsvParser(settings)
    val records = csvParser.iterateRecords(reader).asScala.toList
    csvParser.stopParsing()
    records
  }
}
