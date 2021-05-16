package beam.agentsim.infrastructure.geozone

import java.nio.file.{Files, Path, Paths}
import beam.utils.FileUtils
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

class GeoZoneSpec extends AnyWordSpec with Matchers {

  "GeoZone combined with TopDownEqualDemandGeoZoneHexGenerator and GeoZoneUtil" should {

    "parse coordinates from austin and properly generate already tested GIS file" in {
      FileUtils.usingTemporaryDirectory { tmpFolder =>
        val csvPath = Paths.get("test", "input", "geozone", "austin.csv")
        val wgsCoordinates: Set[WgsCoordinate] = GeoZoneUtil.readWgsCoordinatesFromCsv(csvPath)
        val summary = TopDownEqualDemandH3IndexMapper
          .from(
            geoZone = new GeoZone(wgsCoordinates).includeBoundBoxPoints,
            expectedNumberOfBuckets = 1000,
            initialResolution = 4
          )
          .generateSummary()

        val expectedIndexes = 1001
        val expectedIndexAtResolution9 = 28

        val (shapeFile, indexFile, attributeFile) = {
          val filePrefix: Path = tmpFolder.resolve("output")
          (
            addExtension(filePrefix, ".shp"),
            addExtension(filePrefix, ".shx"),
            addExtension(filePrefix, ".dbf")
          )
        }

        GeoZoneUtil.writeToShapeFile(shapeFile, summary)

        assertResult(expectedIndexes) {
          summary.items.size
        }
        assertResult(expectedIndexAtResolution9) {
          summary.items.count(v => v.index.resolution == 9)
        }
        assert(Files.isRegularFile(shapeFile))
        assert(Files.isRegularFile(indexFile))
        assert(Files.isRegularFile(attributeFile))
      }
    }
  }

  def addExtension(file: Path, extension: String): Path = {
    file.resolveSibling(file.getFileName + extension)
  }

}
