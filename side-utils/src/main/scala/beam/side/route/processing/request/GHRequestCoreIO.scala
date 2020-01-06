package beam.side.route.processing.request

import beam.side.route.model.{Coordinate, GHPaths, Instruction, Url, Way}
import beam.side.route.processing.GHRequest
import com.graphhopper.util.shapes.GHPoint
import com.{graphhopper => gh}
import org.http4s.EntityDecoder
import zio._
import GHRequestCoreIO._
import com.graphhopper.GraphHopper

import scala.collection.JavaConverters._

class GHRequestCoreIO(graphHopper: gh.GraphHopper)(
  implicit val runtime: Runtime[_]
) extends GHRequest[({ type T[A] = RIO[zio.ZEnv, A] })#T] {
  override def request[R](url: Url)(
    implicit decoder: EntityDecoder[
      ({
        type T[A] = RIO[zio.ZEnv, A]
      })#T,
      R
    ]
  ): RIO[zio.ZEnv, R] =
    for {
      req <- RIO.fromEither(url.toGH.toRight(new IllegalArgumentException))
      resp <- RIO
        .effectAsync[zio.ZEnv, gh.GHResponse](cb => cb(RIO.succeed(graphHopper.route(req))))
        .filterOrDie(_.hasErrors)(new IllegalArgumentException("Route not found"))
      path = resp.getBest
      wayPoints = path.getWaypoints.asScala.map(p => Coordinate(p.lon, p.lat))
      instructions = path.getInstructions.asScala.map(inst => Instruction(inst.getDistance, Seq(inst.getLength), inst.getTime)).toSeq
      points = path.getPoints.asScala.map(p => Coordinate(p.lon, p.lat)).toSeq
    } yield GHPaths(Seq(Way(points, instructions, (wayPoints.head, wayPoints.last)))).asInstanceOf[R]
}

object GHRequestCoreIO {

  implicit class UrlGH(url: Url) {

    def toGH: Option[gh.GHRequest] =
      for {
        queryMap <- Some(url.query.toMap[String, _])
        points <- queryMap
          .get("point")
          .map(_.asInstanceOf[Seq[(Double, Double)]].map { case (lat, lon) => new GHPoint(lat, lon) }.toList)
        from       <- points.lift(0)
        to         <- points.lift(1)
        car        <- queryMap.get("vehicle").map(_.asInstanceOf[String])
        calcPoints <- queryMap.get("calc_points").map(_.asInstanceOf[Boolean])
      } yield {
        val req = new gh.GHRequest(from, to).setVehicle(car).setWeighting("fastest")
        req.getHints.put("calc_points", calcPoints).put("instructions", true).put("way_point_max_distance", 1)
        req
      }
  }

  def apply(graphHopper: GraphHopper)(implicit runtime: Runtime[_]): GHRequestCoreIO = new GHRequestCoreIO(graphHopper)(runtime)
}
