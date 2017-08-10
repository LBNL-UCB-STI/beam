package beam.router.gtfs

import java.io.File
import java.nio.file.{Files, Path}
import java.util.zip.ZipFile

import com.conveyal.gtfs.GTFSFeed

object FareModel {
  var agencies: Map[String, Vector[FareRule]] = Map()

  case class FareRule(fare: Fare, routeId: String, originId: String, destinationId: String, containsId: String)
  case class Fare(fareId: String, price: Double, currencyType: String, paymentMethod: Int, transfers: Int, transferDuration: Int)

  def fromDirectory(directory: Path): Unit = {
    agencies = Map()

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
      var fares: Map[String, Fare] = Map()
      var routes: Map[String, Vector[FareRule]] = Map()
      var agencyRules: Vector[FareRule] = Vector()

      feed.fares.forEach((id, fare) => {
        val attr = fare.fare_attribute
        fares += (id -> Fare(attr.fare_id, attr.price, attr.currency_type, attr.payment_method, attr.transfers, attr.transfer_duration))

        fare.fare_rules.forEach(r => {
          val rule: FareRule = FareRule(fares.get(r.fare_id).head, r.route_id, r.origin_id, r.destination_id, r.contains_id)

          if(r.route_id == null) {
            agencyRules = agencyRules :+ rule
          } else {
            var rules = routes.getOrElse(r.route_id, Vector())
            rules = rules :+ rule
            if (!routes.contains(r.route_id)) routes += (r.route_id -> rules)
          }
        })
      })


      feed.agency.forEach((id,_) => {
        feed.routes.values().stream().filter(_.agency_id == id).forEach(route => {
          agencyRules ++= routes.getOrElse(route.route_id, Vector())
        })
        agencies += id -> agencyRules
      })
    }
  }
}
