package beam.agentsim.infrastructure.geozone

import java.io.FileInputStream
import java.nio.file.{Files, Path, Paths}

import beam.utils.FileUtils
import org.apache.commons.codec.digest.DigestUtils
import org.scalatest.{Matchers, WordSpec}

class GeoZoneSpec extends WordSpec with Matchers {

  "GeoZone combined with TopDownEqualDemandsGeoZoneHexGenerator and GeoZoneUtil" should {

    "parse coordinates from austin and properly generate already tested GIS file" in {
      FileUtils.usingTemporaryDirectory { tmpFolder =>
        val csvPath = Paths.get("test", "input", "geozone", "austin.csv")
        val wgsCoordinates = GeoZoneUtil.readWgsCoordinatesFromCsv(csvPath)
        val summary = new GeoZone(wgsCoordinates).includeBoundBoxPoints
          .topDownEqualDemandsGenerator(1000)
          .generate()

        val expectedShapeHash = "dca576d93e0ca3d6e5f940357ca2ec9f"
        val (shapeFile, indexFile, attributeFile) = {
          val filePrefix: Path = tmpFolder.resolve("output")
          (
            addExtension(filePrefix, ".shp"),
            addExtension(filePrefix, ".shx"),
            addExtension(filePrefix, ".dbf")
          )
        }

        GeoZoneUtil.writeToShapeFile(shapeFile, summary)

        assert(Files.isRegularFile(shapeFile))
        assert(Files.isRegularFile(indexFile))
        assert(Files.isRegularFile(attributeFile))
        assertResult(expectedShapeHash) {
          DigestUtils.md5Hex(new FileInputStream(shapeFile.toFile))
        }
      }
    }
  }

  def addExtension(file: Path, extension: String): Path = {
    file.resolveSibling(file.getFileName + extension)
  }

}
