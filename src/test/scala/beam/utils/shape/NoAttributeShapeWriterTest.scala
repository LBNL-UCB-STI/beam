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

class NoAttributeShapeWriterTest extends AnyFunSuite with BeforeAndAfterAll with Matchers {
  private val geometryFactory: GeometryFactory = new GeometryFactory()

  test("Should be able to write Point with attributes") {
    FileUtils.usingTemporaryDirectory { tempFolderPath =>
      val pointFile: File = File.createTempFile("points-", ".shp", tempFolderPath.toFile)
      val shapeWriter = NoAttributeShapeWriter.worldGeodetic[Point](pointFile.getPath)
      val pointLbnl = geometryFactory.createPoint(new Coordinate(-122.2471129, 37.875609))
      shapeWriter.add(
        pointLbnl,
        "Lawrence Berkeley National Laboratory"
      )

      val pointJavaIsland = geometryFactory.createPoint(new Coordinate(107.6636132, -7.3226284))
      shapeWriter.add(
        pointJavaIsland,
        "Java Island"
      )

      val pointAraratMountain = geometryFactory.createPoint(new Coordinate(44.5219596, 39.8307843))
      shapeWriter.add(
        pointAraratMountain,
        "Ararat Mount"
      )
      shapeWriter.write() match {
        case Failure(ex) =>
          fail(ex)
        case Success(OriginalToPersistedFeatureIdMap(originalToPersistedMap)) =>
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
          f1.getAttributeCount shouldBe 1
          verifyGeometryAsPoint(f1, pointLbnl)

          val f2 = featureIdToFeature(originalToPersistedMap("Java Island"))
          f2.getAttributeCount shouldBe 1
          verifyGeometryAsPoint(f2, pointJavaIsland)

          val f3 = featureIdToFeature(originalToPersistedMap("Ararat Mount"))
          f3.getAttributeCount shouldBe 1
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
