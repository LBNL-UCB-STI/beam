package beam.physsim.cchRoutingAssignment

import beam.sim.BeamServices
import com.conveyal.osmlib.OSM
import com.vividsolutions.jts.geom.Coordinate

import scala.collection.JavaConverters._

/**
  * Cache for OSM nodes and ways
  */
class OsmInfoHolder(beamServices: BeamServices) {
  private val osm = new OSM(beamServices.beamConfig.beam.routing.r5.osmMapdbFile)

  private val id2NodeIds: Map[Long, Seq[Long]] = osm.ways.asScala.map { case (id, way) =>
    id.toLong -> way.nodes.toSeq
  }.toMap

  private val id2NodeCoordinate = osm.nodes.asScala.map { case (id, node) =>
    id -> new Coordinate(node.getLat, node.getLon)
  }.toMap

  osm.close()

  def getCoordinatesForWayId(firstWayId: Long): Seq[Coordinate] =
    id2NodeIds(firstWayId)
      .flatMap(x => id2NodeCoordinate.get(x))
}
