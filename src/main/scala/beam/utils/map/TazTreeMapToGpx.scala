package beam.utils.map

import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.BeamServices
import beam.sim.config.BeamConfig
import beam.utils.map.R5NetworkPlayground.prepareConfig
import com.google.common.io.Files
import com.typesafe.scalalogging.LazyLogging

object TazTreeMapToGpx extends LazyLogging {

  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, true)
    val beamConfig = BeamConfig(cfg)
    val geoUtils = new beam.sim.common.GeoUtils {
      override def localCRS: String = "epsg:26910"
    }
    val tazTreeMap: TAZTreeMap = TAZTreeMap.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)

    val tazPoints = tazTreeMap.getTAZs.map { taz =>
      GpxPoint(taz.tazId.toString, geoUtils.utm2Wgs(taz.coord))
    }.toArray

    val fileName = Files.getNameWithoutExtension(beamConfig.beam.agentsim.taz.filePath)
    GpxWriter.write(fileName + ".gpx", tazPoints)

    GeoJsonToGpxConvertor.renderEnvelope(tazPoints, fileName + "_envelope.gpx")
  }
}
