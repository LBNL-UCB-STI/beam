package beam.utils.map

import java.io.File

import beam.utils.FileUtils
import com.vividsolutions.jts.geom.{Coordinate, GeometryFactory, Point}
import org.matsim.core.utils.gis.ShapeFileReader
import org.opengis.feature.simple.SimpleFeature
import org.scalatest.{BeforeAndAfterAll, FunSuite, Matchers}

import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._

class ShapeWriterTest extends FunSuite with BeforeAndAfterAll with Matchers {
  private val geometryFactory: GeometryFactory = new GeometryFactory()

  test("Should be able to write Point") {
    FileUtils.usingTemporaryDirectory { tempFolderPath =>
      val pointFile: File = File.createTempFile("points-", ".shp", tempFolderPath.toFile)

      val attribToClass: Map[String, Class[_]] =
        Map("name" -> classOf[String], "int" -> classOf[java.lang.Integer], "double" -> classOf[java.lang.Double])
      val shapeWriter = ShapeWriter.worldGeodetic[Point](pointFile.getPath, attribToClass)

      val pointLbnl = geometryFactory.createPoint(new Coordinate(-122.2471129, 37.875609))
      shapeWriter.add(
        pointLbnl,
        "Lawrence Berkeley National Laboratory",
        Map("name" -> "Lawrence Berkeley National Laboratory", "int" -> 1, "double" -> 1.0)
      )

      val pointJavaIsland = geometryFactory.createPoint(new Coordinate(107.6636132, -7.3226284))
      shapeWriter.add(pointJavaIsland, "Java Island", Map("name" -> "Java Island", "int" -> 2, "double" -> 2.0))

      val pointAraratMountain = geometryFactory.createPoint(new Coordinate(44.5219596, 39.8307843))
      shapeWriter.add(pointAraratMountain, "Ararat Mount", Map("name" -> "Ararat Mount", "int" -> 3, "double" -> 3.0))
      shapeWriter.write() match {
        case Failure(ex) =>
          fail(ex)
        case Success((originalToPersistedMap)) =>
          val featureIdToFeature = ShapeFileReader
            .getAllFeatures(pointFile.getPath)
            .asScala
            .map { f =>
              f.getID -> f
            }
            .toMap
          assert(featureIdToFeature.contains(originalToPersistedMap("Lawrence Berkeley National Laboratory")))
          assert(featureIdToFeature.contains(originalToPersistedMap("Java Island")))
          assert(featureIdToFeature.contains(originalToPersistedMap("Ararat Mount")))

          val f1 = featureIdToFeature(originalToPersistedMap("Lawrence Berkeley National Laboratory"))
          f1.getAttribute("name") shouldBe "Lawrence Berkeley National Laboratory"
          f1.getAttribute("int") shouldBe 1
          f1.getAttribute("double") shouldBe 1.0
          verifyGeometryAsPoint(f1, pointLbnl)

          val f2 = featureIdToFeature(originalToPersistedMap("Java Island"))
          f2.getAttribute("name") shouldBe "Java Island"
          f2.getAttribute("int") shouldBe 2
          f2.getAttribute("double") shouldBe 2.0
          verifyGeometryAsPoint(f2, pointJavaIsland)

          val f3 = featureIdToFeature(originalToPersistedMap("Ararat Mount"))
          f3.getAttribute("name") shouldBe "Ararat Mount"
          f3.getAttribute("int") shouldBe 3
          f3.getAttribute("double") shouldBe 3.0
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
