package beam.agentsim.infrastructure

import java.io.File
import java.util
import java.util.ArrayList

import beam.utils.scripts.HasXY.wgs2Utm
import beam.utils.scripts.QuadTreeExtent
import com.vividsolutions.jts.geom.Geometry
import org.geotools.data.simple.SimpleFeatureIterator
import org.geotools.data.{FileDataStore, FileDataStoreFinder}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.misc.Counter
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters._


object TAZMain extends App {
  val shapeFile: String = "Y:\\tmp\\beam\\tl_2011_06_taz10\\tl_2011_06_taz10.shp";
  print(shapeFile)
  new TAZ(shapeFile)
}

class TAZ(shapeFilePath: String) {
  val tazQuadTree: QuadTree[TazId] = initQuadTree()


  def getId(x: Double, y: Double): TazId={
    tazQuadTree.getClosest(x,y)
  }

  private def quadTreeExtentFromShapeFile(features: util.Collection[SimpleFeature]): QuadTreeBounds = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          val ca = wgs2Utm(g.getEnvelope.getEnvelopeInternal)
          minX = Math.min(minX, ca.getMinX)
          minY = Math.min(minY, ca.getMinY)
          maxX = Math.max(maxX, ca.getMaxX)
          maxY = Math.max(maxY, ca.getMaxY)
        case _ =>
      }
    }
    QuadTreeBounds(minX, minY, maxX, maxY)
  }


  private def readFeaturesFromShapeFile():util.Collection[SimpleFeature]={
    val dataFile = new File(shapeFilePath)
    val store = FileDataStoreFinder.getDataStore(dataFile)
    val featureSource= store.getFeatureSource
    var ft:SimpleFeature = null
    val it = featureSource.getFeatures.features
    val featureSet = new util.ArrayList[SimpleFeature]
   print("features to read #" + featureSource.getFeatures.size)
    val cnt = new Counter("features read #")

    while (it.hasNext){
      ft = it.next()
      featureSet.add(ft)
      cnt.incCounter()

    }
    cnt.printCounter()
    it.close()
    featureSet
  }

  private def initQuadTree(): QuadTree[TazId] = {
    val features: util.Collection[SimpleFeature] = readFeaturesFromShapeFile()
    val quadTreeBounds: QuadTreeBounds = quadTreeExtentFromShapeFile(features)

    val tazQuadTree: QuadTree[TazId] = new QuadTree[TazId](quadTreeBounds.minx, quadTreeBounds.miny, quadTreeBounds.maxx, quadTreeBounds.maxy)

    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          tazQuadTree.put(g.getCoordinate.x, g.getCoordinate.y, TazId(f.getID.toInt))
          print(f.getID.toInt)
        case _ =>
      }


    }
    tazQuadTree
  }



}

case class QuadTreeBounds(minx: Double, miny: Double, maxx: Double, maxy: Double)

case class TazId(id: Int)






