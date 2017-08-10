package beam.router.gtfs

import java.io.File
import java.nio.file.{Files, Path}
import java.util.zip.ZipFile

import com.conveyal.gtfs.GTFSFeed

object FareModel {
  var routes: Map[String, Route] = Map()
  var fares: Map[String, Fare] = Map()

  case class Route(routeId: String, var fareRules: Vector[FareRule])
  case class FareRule(fare: Fare, originId: String, destinationId: String, containsId: String)
  case class Fare(fareId: String, price: Float, currencyType: String, paymentMethod: Int, transfers: Int, transferDuration: Int)

  def fromDirectory(directory: Path): Unit = {
    routes = Map()
    fares = Map()

    if(Files.isDirectory(directory)) {
      directory.toFile.listFiles(hasFares(_)).map(_.getAbsolutePath).foreach(p => {
        loadFares(GTFSFeed.fromFile(p))
      })
    }

    def hasFares(file: File): Boolean = {
      var isFareExist = false
      if(file.getName.endsWith(".zip")) {
        try {
          val zip = new ZipFile(file)
          isFareExist = zip.getEntry("fare_attributes.txt") != null
          zip.close()
        } catch {
          case _: Throwable => // do nothing
        }
      }
      isFareExist
    }

    def loadFares(feed: GTFSFeed) = {
      feed.fares.forEach((id, fare) => {
        val attr = fare.fare_attribute

        fares += (id -> Fare(attr.fare_id, attr.price.toFloat, attr.currency_type, attr.payment_method, attr.transfers, attr.transfer_duration))

        var fareRoutes: Vector[Route] = Vector()
        fare.fare_rules.forEach(r => {
          val rule: FareRule = FareRule(fares.get(r.fare_id).head, r.origin_id, r.destination_id, r.contains_id)

          if(r.route_id == null) {
            fareRoutes.foreach(fRule => fRule.fareRules = fRule.fareRules :+ rule)
          } else {
            val route: Route = routes.getOrElse(r.route_id, Route(r.route_id, Vector()))
            route.fareRules = route.fareRules :+ rule
            if (!routes.contains(r.route_id)) routes += (r.route_id -> route)
            fareRoutes = fareRoutes :+ route
          }
        })
      })
    }
  }
}
