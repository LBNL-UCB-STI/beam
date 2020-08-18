package beam.utils.google_routes_db

import scala.collection.JavaConverters._
import scala.collection.immutable

package object elasticsearch {

  val googleRoutesIndex = "google_route"

  val mapping: java.util.Map[String, Object] = mapAsJavaMap(Map(
    "properties" → mapAsJavaMap(Map(
      "bound_northeast" → mapAsJavaMap(Map("type" → "geo_point")),
      "bound_southwest" → mapAsJavaMap(Map("type" → "geo_point")),
      "copyrights" → mapAsJavaMap(Map("type" → "text")),
      "summary" → mapAsJavaMap(Map("type" → "text")),
      "leg_distance" → mapAsJavaMap(Map("type" → "integer")),
      "leg_distance_txt" → mapAsJavaMap(Map("type" → "text")),
      "leg_duration" → mapAsJavaMap(Map("type" → "integer")),
      "leg_duration_txt" → mapAsJavaMap(Map("type" → "text")),
      "leg_end_address" → mapAsJavaMap(Map("type" → "text")),
      "leg_end_location" → mapAsJavaMap(Map("type" → "geo_point")),
      "leg_start_address" → mapAsJavaMap(Map("type" → "text")),
      "leg_start_location" → mapAsJavaMap(Map("type" → "geo_point"))
    ))
  ))

  def googleRoutesToESMap(
    grsSeq: immutable.Seq[json.GoogleRoutes],
    jsonFileUri: String
  ): immutable.Seq[java.util.Map[String, Object]] =
    for {
      grs ← grsSeq
      route ← grs.routes
      leg ← route.legs
    } yield mapAsJavaMap(Map[String, Object](
      "json_file_uri" → jsonFileUri,
      "bound_northeast" → coordToESMap(route.bounds.northeast),
      "bound_southwest" → coordToESMap(route.bounds.southwest),
      "copyrights" → route.copyrights,
      "summary" → route.summary,
      "leg_distance" → leg.distance.value.asInstanceOf[Object],
      "leg_distance_txt" → leg.distance.text,
      "leg_duration" → leg.duration.value.asInstanceOf[Object],
      "leg_duration_txt" → leg.duration.text,
      "leg_end_address" → leg.endAddress,
      "leg_end_location" → coordToESMap(leg.endLocation),
      "leg_start_address" → leg.startAddress.getOrElse("<empty>"),
      "leg_start_location" → coordToESMap(leg.startLocation)
    ))

  def coordToESMap(coord: json.GoogleRoute.Coord): java.util.Map[String, Object] =
    mapAsJavaMap(Map("lat" → coord.lat.asInstanceOf[Object], "lon" → coord.lng.asInstanceOf[Object]))
}
