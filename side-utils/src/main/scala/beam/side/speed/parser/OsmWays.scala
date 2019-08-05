package beam.side.speed.parser
import java.nio.file.{Path, Paths}

import beam.side.speed.model.OsmNodeSpeed
import com.conveyal.osmlib.{OSM, Way}
import com.conveyal.r5.kryo.KryoNetworkSerializer

import scala.collection.JavaConverters._
import scala.util.{Success, Try}

case class OsmCursor(id: Long, index: Int, lenght: Double)

class OsmWays(osmPath: Path, r5Path: Path) {
  private val highways = Map(
    "motorway"       -> 75 * 1.60934 / 3.6,
    "motorway_link"  -> (2.0 / 3.0) * 75 * 1.60934 / 3.6,
    "primary"        -> 65 * 1.60934 / 3.6,
    "primary_link"   -> 0.75 * 65 * 1.60934 / 3.6,
    "trunk"          -> 60 * 1.60934 / 3.6,
    "trunk_link"     -> 0.625 * 60 * 1.60934 / 3.6,
    "secondary"      -> 60 * 1.60934 / 3.6,
    "secondary_link" -> (2.0 / 3.0) * 60 * 1.60934 / 3.6,
    "tertiary"       -> 55 * 1.60934 / 3.6,
    "tertiary_link"  -> (2.0 / 3.0) * 55 * 1.60934 / 3.6,
    "minor"          -> 25 * 1.60934 / 3.6,
    "residential"    -> 25 * 1.60934 / 3.6,
    "living_street"  -> 25 * 1.60934 / 3.6,
    "unclassified"   -> 28 * 1.60934 / 3.6,
  )

  private val osm = new OSM(osmPath.toAbsolutePath.toString).ways.asScala
  private val edgeCursor = KryoNetworkSerializer.read(r5Path.toFile).streetLayer.edgeStore.getCursor

  lazy val nodes: Iterator[OsmNodeSpeed] = {
    var idx = -1l
    Iterator
      .continually(edgeCursor.advance())
      .map(_ => Try(OsmCursor(edgeCursor.getOSMID, edgeCursor.getEdgeIndex, edgeCursor.getLengthM)))
      .takeWhile(_.isSuccess)
      .collect {
        case Success(OsmCursor(o, e, l)) if idx < e =>
          idx = e
          osm.get(o).map(w => (e, o, w, l))
      }
      .collect {
        case Some((eId, id, w, l)) =>
          val (s, t) = waySpeed(w)
          OsmNodeSpeed(eId, id, w.nodes(0), w.nodes(1), s, t, l)
      }
  }

  private def waySpeed(way: Way): (Float, String) = {
    val hTags = Option(way.getTag("highway"))
      .map(_.replaceAll("[\\[\\] ']", ""))
      .map(_.split(","))
      .toSeq
      .flatten

    val (hTag, sp) = Option(way.getTag("maxspeed"))
      .flatMap {
        case s if s.contains("mph") =>
          Try(s.replace("mph", "").trim.toDouble * 1.609344 / 3.6).toOption
            .map(spe => hTags.headOption.getOrElse("unclassified") -> spe)
        case s =>
          Try(s.toDouble / 3.6).toOption
            .map(spe => hTags.headOption.getOrElse("unclassified") -> spe)
      }
      .getOrElse(
        hTags.foldLeft("unclassified" -> highways("unclassified"))(
          (z, a) => Seq(z, a -> highways.getOrElse(a, 28 * 1.60934 / 3.6)).maxBy(_._2)
        )
      )
    sp.toFloat -> hTag
  }
}

object OsmWays {
  def apply(osmPath: String, r5Path: String): OsmWays = new OsmWays(Paths.get(osmPath), Paths.get(r5Path))
}
