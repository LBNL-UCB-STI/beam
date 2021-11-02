package beam.utils

import beam.utils.Network2ShapeFile.networkToShapeFile
import beam.utils.map.ShapefileReader
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.NetworkReaderMatsimV2
import org.matsim.core.utils.geometry.geotools.MGC
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.operation.MathTransform
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import scala.jdk.CollectionConverters.collectionAsScalaIterableConverter

class Network2ShapeFileTest extends AnyWordSpecLike with Matchers {
  "Network2ShapeFile transformation" should {
    "generate the same number of features" in {
      val beamvilleNetwork = "test/test-resources/beam/beamville-physsim-network.xml"
      val outputShapeFile = "output/beamville-network.shp"

      val crsString = "epsg:32631"
      val crs = MGC.getCRS(crsString)

      // write out shapefile
      networkToShapeFile(beamvilleNetwork, outputShapeFile, crs, _ => true)

      // read network agaim
      val network = NetworkUtils.createNetwork()
      val reader = new NetworkReaderMatsimV2(network)
      reader.readFile(beamvilleNetwork)

      @SuppressWarnings(Array("UnusedMethodParameter"))
      def mapToID2AllProperties(mathTransform: MathTransform, feature: SimpleFeature): (String, Map[String, AnyRef]) = {
        val featureId = feature.getAttribute("ID").toString
        val allProps =
          feature.getProperties.asScala.map(property => property.getName.toString -> property.getValue).toMap

        featureId -> allProps
      }

      // read all features from shp file
      val shpFeatures = ShapefileReader.read(crsString, outputShapeFile, _ => true, mapToID2AllProperties).toMap

      // compare the lengths
      val originalNumberOfLinks = network.getLinks.size()
      val resultCount = shpFeatures.size

      resultCount shouldBe originalNumberOfLinks
    }
  }
}
