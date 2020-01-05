package beam.side.route.processing.request

import beam.side.route.model.Url
import beam.side.route.processing.GHRequest
import com.graphhopper.util.shapes.GHPoint
import com.{graphhopper => gh}
import org.http4s.EntityDecoder
import zio._
import GHRequestCoreIO._

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
        .filterOrDie(_.hasErrors)(new IllegalArgumentException)
    } yield { resp.getBest.getInstructions }
}

object GHRequestCoreIO {

  implicit class UrlGH(url: Url) {

    def toGH: Option[gh.GHRequest] =
      for {
        queryMap <- Some(url.query.toMap)
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
}
