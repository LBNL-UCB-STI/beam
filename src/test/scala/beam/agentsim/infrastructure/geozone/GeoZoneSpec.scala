package beam.agentsim.infrastructure.geozone

import java.nio.file.Paths

import org.scalatest.{Matchers, WordSpec}

class GeoZoneSpec extends WordSpec with Matchers {

  "GeoZone" should {

    "be generated from a set of points" in {
      val csvPath = Paths.get("test", "input", "h3", "austin.csv")
      val wgsCoordinates = GeoZoneUtil.readWgsCoordinatesFromCsv(csvPath)
      val summary = new GeoZone(wgsCoordinates)
        .includeBoundBoxPoints
        .topDownEqualDemandsGenerator(1000)
        .generate()
      GeoZoneUtil.writeToShapeFile("output.shp", summary)
    }
  }

}
