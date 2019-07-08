package beam.side.speed.parser
import java.nio.file.{Path, Paths}

import beam.side.speed.model.OsmNodeSpeed
import com.conveyal.osmlib.{OSM, Way}
import com.conveyal.r5.kryo.KryoNetworkSerializer

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

class OsmWays(osmPath: Path, r5Path: Path) {
  private val highways = Map(
    "motorway"       -> 75 * 1.60934 / 3.6,
    "motorway_link"  -> 2 / 3 * 75 * 1.60934 / 3.6,
    "primary"        -> 65 * 1.60934 / 3.6,
    "primary_link"   -> 0.75 * 65 * 1.60934 / 3.6,
    "trunk"          -> 60 * 1.60934 / 3.6,
    "trunk_link"     -> 0.625 * 60 * 1.60934 / 3.6,
    "secondary"      -> 60 * 1.60934 / 3.6,
    "secondary_link" -> 2 / 3 * 60 * 1.60934 / 3.6,
    "tertiary"       -> 55 * 1.60934 / 3.6,
    "tertiary_link"  -> 2 / 3 * 55 * 1.60934 / 3.6,
    "minor"          -> 25 * 1.60934 / 3.6,
    "residential"    -> 25 * 1.60934 / 3.6,
    "living_street"  -> 25 * 1.60934 / 3.6,
    "unclassified"   -> 28 * 1.60934 / 3.6,
  )

  private val osm = new OSM(osmPath.toAbsolutePath.toString).ways.asScala
  private val edgeCursor = KryoNetworkSerializer.read(r5Path.toFile).streetLayer.edgeStore.getCursor

  lazy val nodes: Iterator[OsmNodeSpeed] = Iterator
    .iterate(edgeCursor.advance())(_ => edgeCursor.advance())
    .map(_ => Try((edgeCursor.getOSMID, edgeCursor.isBackward)))
    .takeWhile(_.isSuccess)
    .collect {
      case Success((t, b)) => osm.get(t).map(w => t -> (w, b))
    }
    .collect {
      case Some((id, (w, false))) =>
        OsmNodeSpeed(id, w.nodes(0), w.nodes(1), waySpeed(w))
      case Some((id, (w, true))) =>
        OsmNodeSpeed(id, w.nodes(1), w.nodes(0), waySpeed(w))
    }

  private def waySpeed(way: Way): Float = {
    val hTag = Option(way.getTag("highway")).getOrElse("unclassified")
    Option(way.getTag("maxspeed"))
      .flatMap {
        case s if s.contains("mph") => Try(s.replace("mph", "").trim.toFloat * 1.609344 / 3.6).toOption
        case s                      => Try(s.toFloat / 3.6).toOption
      }
      .getOrElse(highways.getOrElse(hTag, 28 * 1.60934 / 3.6))
      .toFloat
  }
}

object OsmWays {
  def apply(osmPath: String, r5Path: String): OsmWays = new OsmWays(Paths.get(osmPath), Paths.get(r5Path))
}
