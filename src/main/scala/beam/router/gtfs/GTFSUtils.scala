package beam.router.gtfs

import beam.sim.common.GeoUtils
import beam.utils.matsim_conversion.ShapeUtils
import beam.utils.matsim_conversion.ShapeUtils.HasCoord
import com.conveyal.gtfs.GTFSFeed
import com.conveyal.gtfs.model.Stop
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.collections.QuadTree

import java.nio.file.{Files, Paths}
import scala.collection.JavaConverters._

/**
  * @author Dmitry Openkov
  */
object GTFSUtils {

  def loadGTFS(dataDir: String): IndexedSeq[GTFSFeed] = {
    val r5Path = Paths.get(dataDir)
    Files
      .find(
        r5Path,
        1,
        (path, fileAttributes) =>
          fileAttributes.isRegularFile && path.getFileName.toString.toLowerCase().endsWith(".zip")
      )
      .iterator()
      .asScala
      .map(filePath => GTFSFeed.fromFile(filePath.toString))
      .toIndexedSeq
  }

  def toQuadTree(stops: Seq[Stop], geo: GeoUtils): QuadTree[Stop] = {
    implicit val hasCoord: HasCoord[Stop] = (a: Stop) => geo.wgs2Utm(new Coord(a.stop_lon, a.stop_lat))
    ShapeUtils.quadTree(stops)
  }
}
