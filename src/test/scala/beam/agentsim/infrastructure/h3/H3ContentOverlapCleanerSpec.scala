package beam.agentsim.infrastructure.h3

import java.io.{FileInputStream, InputStreamReader}
import java.nio.file.{Path, Paths}

import scala.collection.mutable.ArrayBuffer

import org.scalatest.{Matchers, WordSpec}
import org.supercsv.io.{CsvMapReader, ICsvMapReader}
import org.supercsv.prefs.CsvPreference

class H3ContentOverlapCleanerSpec extends WordSpec with Matchers {

  private def readerFromFile(filePath: Path): java.io.Reader = {
    new InputStreamReader(new FileInputStream(filePath.toFile))
  }

  def readPointsFromCsv(relativePath: Path): Set[H3Point] = {
    var mapReader: ICsvMapReader = null
    val res = ArrayBuffer[H3Point]()
    try {
      mapReader = new CsvMapReader(readerFromFile(relativePath), CsvPreference.STANDARD_PREFERENCE)
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {
        val coordX = line.get("longitude").toDouble
        val coordY = line.get("latitude").toDouble
        res.append(H3Point(coordY, coordX))
        line = mapReader.read(header: _*)
      }
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res.toSet
  }

  "H3ContentOverlapCleaner" should {
    "removeOverlaps" in {
      val allPoints = readPointsFromCsv(Paths.get("test", "input", "h3", "austin.csv"))

      val content = H3Content.fromPoints(allPoints, H3Content.MinResolution)
      H3Util.writeToShapeFile("before.shp", content)

      val expectedOutputSize = 50
      val breakdownExecutor = BucketBreakdownExecutor(content, expectedOutputSize)

      val result = breakdownExecutor.execute()
      val cleaned1 = new H3ContentOverlapCleaner(result).execute()

      H3Util.writeToShapeFile("after.shp", cleaned1)
    }
  }

}
