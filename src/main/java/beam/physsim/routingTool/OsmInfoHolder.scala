package beam.physsim.routingTool

import beam.sim.BeamServices
import com.conveyal.osmlib.OSM
import com.google.inject.Inject
import com.vividsolutions.jts.geom.Coordinate

import scala.collection.JavaConverters._

class OsmInfoHolder @Inject()(beamServices: BeamServices) {
  private val osm = new OSM(beamServices.beamConfig.beam.routing.r5.osmMapdbFile)

  val id2NodeIds: Map[java.lang.Long, List[java.lang.Long]] = osm.ways.asScala.map {
    case (id, way) =>
      id -> way.nodes.map(_.asInstanceOf[java.lang.Long]).toList
  }.toMap

  val id2NodeCoordinate = osm.nodes.asScala.map {
    case (id, node) =>
      id -> new Coordinate(node.getLat, node.getLon)
  }.toMap

  osm.close()

  def getCoordinatesForWayId(firstWayId: Long): java.util.List[Coordinate] =
    id2NodeIds(firstWayId)
      .map(x => id2NodeCoordinate.getOrElse(x, new Coordinate(0.0, 0.0)))
      .asJava
}
