package beam.utils

import java.util.Random

import beam.utils.matsim_conversion.ShapeUtils.QuadTreeBounds
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.mutable.ListBuffer

object CoordInBoundsUtil extends App {

  //val plansFilePath = "C:/ns/beam-projects/beam-master4/test/input/beamville/test-data/urbansim/urbansim2/plans.csv"
  val plansFilePath = "D:/beam/test/input/sfbay/test-data/urbansim/plans.csv"

  // beamServices.beamConfig.beam.spatial.localCRS epsg:32631
  // mNetBuilder.toCRS = "EPSG:26910"     # UTM10N
  val sourceCRS = "EPSG:4326"
  //val targetCRS = "epsg:32631"
  val targetCRS = "EPSG:26910"
  val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation(sourceCRS, targetCRS)

  /*lat, lng, coord 37.336730003490004, -121.88562672146001, [x=500000.0000000008][y=-9997964.942939684]
  lat, lng, coord 37.3827565381698, -121.92953484746799, [x=500000.0000000008][y=-9997964.942939684]
  lat, lng, coord 37.336730003490004, -121.88562672146001, [x=500000.0000000008][y=-9997964.942939684]*/

  val minx = 438333.96309192316
  val maxx = 673371.0017821543
  val miny = 4076139.064808234
  val maxy = 4321404.5918796975

  val envelop = QuadTreeBounds(minx, miny, maxx, maxy)
  val radius = 10000
  val randomSeed = 4711
  val rand: Random = new Random(randomSeed)
  //val coords = sampleCoords()

  val coords = readPlansFile(plansFilePath)

  println("Loaded coord 10 outWriter of " + coords.size)
  coords.take(10).foreach(println)

  val transformedCoords = coords.map { coord =>
    val coordUtm = wgs2Utm.transform(coord)
    val coordUtmWithRadius = new Coord(
      coordUtm.getX + radius * (rand.nextDouble() - 0.5),
      coordUtm.getY + radius * (rand.nextDouble() - 0.5)
    )
    (coord, coordUtmWithRadius)
  }

  val filteredCoords = transformedCoords.filter { coordSet =>
    if (coordSet._2.getX.equals(4013.4729453296563)) {
      //x=-4515.877289622377
      println("X coordinate found")
    }
    !containsOrEqualsCoords(envelop, coordSet._2)
  }

  println("Filtered coordinates those are not in bounds 10 outWriter of " + filteredCoords.size)
  filteredCoords.take(10).foreach(println)

  def containsOrEqualsCoords(boundingBox: QuadTreeBounds, coord: Coord): Boolean = {
    boundingBox.minx <= coord.getX && coord.getX <= boundingBox.maxx &&
    boundingBox.miny <= coord.getY && coord.getY <= boundingBox.maxy
  }

  private def readCsvFileByLine[A](filePath: String, z: A)(readLine: (java.util.Map[String, String], A) => A): A = {
    FileUtils.using(new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)) {
      mapReader =>
        var res: A = z
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          res = readLine(line, res)
          line = mapReader.read(header: _*)
        }
        res
    }
  }

  def sampleCoords(): ListBuffer[Coord] = {
    val coords = new ListBuffer[Coord]()

    coords += new Coord(-121.88562672146001, 37.336730003490004)
    coords += new Coord(-121.92953484746799, 37.3827565381698)
    coords += new Coord(-121.88562672146001, 37.336730003490004)
    coords.foreach { coord: Coord =>
      println(coord.toString() + " -> " + wgs2Utm.transform(coord))
    }
    coords
  }

  def readPlansFile(filePath: String): ListBuffer[Coord] = {
    readCsvFileByLine(filePath, ListBuffer[Coord]()) { case (line, acc) =>
      val lng = line.get("x")
      val lat = line.get("y")

      (lng, lat) match {
        case (lng_, lat_) if lng_ != null && lat_ != null => acc += new Coord(lng.toDouble, lat.toDouble)
        case _                                            => acc
      }

    }
  }

}
