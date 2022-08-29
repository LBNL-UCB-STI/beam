package beam.utils.shape

import java.io.File

import beam.utils.FileUtils
import beam.utils.shape.ShapeWriter.OriginalToPersistedFeatureIdMap
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Point}
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.simple.SimpleFeature
import org.scalatest.BeforeAndAfterAll
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._
import scala.util.{Failure, Success}

private case class TestAttributes(
  name: String,
  byte: Byte,
  short: Short,
  int: Int,
  long: Long,
  double: Double
) extends Attributes

class ShapeWriterTest extends AnyFunSuite with BeforeAndAfterAll with Matchers {
  private val geometryFactory: GeometryFactory = new GeometryFactory()

  test("Should be able to write Point with attributes") {
    FileUtils.usingTemporaryDirectory { tempFolderPath =>
      val pointFile: File = File.createTempFile("points-", ".shp", tempFolderPath.toFile)
      val shapeWriter = ShapeWriter.worldGeodetic[Point, TestAttributes](pointFile.getPath)
      val pointLbnl = geometryFactory.createPoint(new Coordinate(-122.2471129, 37.875609))
      shapeWriter.add(
        pointLbnl,
        "Lawrence Berkeley National Laboratory",
        TestAttributes(
          name = "Lawrence Berkeley National Laboratory",
          byte = 1,
          short = 2,
          int = 3,
          long = 4L,
          double = 5.0d
        )
      )

      val pointJavaIsland = geometryFactory.createPoint(new Coordinate(107.6636132, -7.3226284))
      shapeWriter.add(
        pointJavaIsland,
        "Java Island",
        TestAttributes(name = "Java Island", byte = 6, short = 7, int = 8, long = 9, double = 10.0)
      )

      val pointAraratMountain = geometryFactory.createPoint(new Coordinate(44.5219596, 39.8307843))
      shapeWriter.add(
        pointAraratMountain,
        "Ararat Mount",
        TestAttributes(name = "Ararat Mount", byte = 11, short = 12, int = 13, long = 14, double = 15.0)
      )
      shapeWriter.write() match {
        case Failure(ex) =>
          fail(ex)
        case Success(OriginalToPersistedFeatureIdMap(originalToPersistedMap)) =>
          val featureIdToFeature = ShapeFileReader
            .getAllFeatures(pointFile.getPath)
            .asScala
            .map(f => f.getID -> f)
            .toMap
          assert(featureIdToFeature.contains(originalToPersistedMap("Lawrence Berkeley National Laboratory")))
          assert(featureIdToFeature.contains(originalToPersistedMap("Java Island")))
          assert(featureIdToFeature.contains(originalToPersistedMap("Ararat Mount")))

          val f1 = featureIdToFeature(originalToPersistedMap("Lawrence Berkeley National Laboratory"))
          f1.getAttribute("name") shouldBe "Lawrence Berkeley National Laboratory"
          f1.getAttribute("byte") shouldBe 1
          f1.getAttribute("short") shouldBe 2
          f1.getAttribute("int") shouldBe 3
          f1.getAttribute("long") shouldBe 4
          f1.getAttribute("double") shouldBe 5.0d
          verifyGeometryAsPoint(f1, pointLbnl)

          val f2 = featureIdToFeature(originalToPersistedMap("Java Island"))
          f2.getAttribute("name") shouldBe "Java Island"
          f2.getAttribute("byte") shouldBe 6
          f2.getAttribute("short") shouldBe 7
          f2.getAttribute("int") shouldBe 8
          f2.getAttribute("long") shouldBe 9
          f2.getAttribute("double") shouldBe 10.0d
          verifyGeometryAsPoint(f2, pointJavaIsland)

          val f3 = featureIdToFeature(originalToPersistedMap("Ararat Mount"))
          f3.getAttribute("name") shouldBe "Ararat Mount"
          f3.getAttribute("byte") shouldBe 11
          f3.getAttribute("short") shouldBe 12
          f3.getAttribute("int") shouldBe 13
          f3.getAttribute("long") shouldBe 14
          f3.getAttribute("double") shouldBe 15.0d
          verifyGeometryAsPoint(f3, pointAraratMountain)
      }
    }
  }

  def verifyGeometryAsPoint(feature: SimpleFeature, expectedPoint: Point): Unit = {
    feature.getDefaultGeometry match {
      case p: Point =>
        assert(p == expectedPoint)
      case x => fail(s"Expected to see the ${classOf[Point]}, but got ${x.getClass}")
    }
  }
}
