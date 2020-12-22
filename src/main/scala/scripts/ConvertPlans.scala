package scripts

import beam.sim.common.GeoUtils
import beam.utils.FileUtils
import org.matsim.api.core.v01.Coord
import org.matsim.core.utils.io.IOUtils

object ConvertPlans extends App {

  private val geo: GeoUtils = new GeoUtils {
    override def localCRS: String = "epsg:26910"
  }

  FileUtils.using(IOUtils.getBufferedWriter("/home/crixal/Downloads/plans_4326.csv")) { bw =>
    FileUtils.using(IOUtils.getBufferedReader("/home/crixal/Downloads/plans.csv")) { br =>
      var line = br.readLine()
      while (line != null) {
        val parts = line.split(",")
        if (parts(4) == "activity") {
          val x = parts(7)
          val y = parts(8)
          val coord4326 = geo.utm2Wgs.transform(new Coord(x.toDouble, y.toDouble))
          parts(7) = coord4326.getX.toString
          parts(8) = coord4326.getY.toString
          bw.write(parts.mkString(","))
        } else {
          bw.write(line)
        }
        bw.newLine()
        line = br.readLine()
      }
    }
  }
}
