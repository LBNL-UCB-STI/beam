package beam.router.gtfs

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.zip.ZipFile

import com.conveyal.gtfs.GTFSFeed
import com.conveyal.r5.api.util.{TransitJourneyID, TransitSegment}

object FareModel {
  var agencies: Map[String, Vector[FareRule]] = Map()
//  lazy val containRules = agencies.map(a => a._1 -> a._2.filter(r => r.containsId != null).groupBy(_.fare))

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
            routes += (r.route_id -> rules)
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

  def calcFare(agencyId: String, routeId: String, fromId: String, toId: String, ts: Vector[(TransitSegment, TransitJourneyID)] = null) = {

    // Fare depends on which route the itinerary uses AND Fare depends on origin or destination stations
    // BUT Fare depends on which zones the itinerary passes through, is group rule and apply separately
    def baseRule(r: FareRule) =
      (r.routeId == routeId || r.routeId == null) &&
        (r.originId == fromId || r.originId == null) &&
        (r.destinationId == toId || r.destinationId == null)

    val _containsIds = if(ts == null || ts.isEmpty) Vector(fromId, toId).sorted else
      ts.map(i => Vector(i._1.from.stopId.split((":"))(1), i._1.to.stopId.split((":"))(1))).flatten.distinct.sorted

    //Fare depends on which zones the itinerary passes through
    def containsRule(t: (Fare, Vector[FareRule])) =
      t._2.map(_.routeId).distinct.forall(id => id == routeId || id == null) &&
        t._2.map(_.containsId).sorted.equals(_containsIds)

    val baseRules = FareModel.agencies.getOrElse(agencyId, Vector()).filter(baseRule(_))
    //      val ruleCount = baseRules.count(_.fare != null);
    //      if(ruleCount > 1) {
    //        log.debug(s"filtered rule count for route $routeId is $ruleCount")
    //      }

    baseRules.withFilter(_.containsId == null).map(_.fare.price).sum +
      baseRules.filter(_.containsId != null).groupBy(_.fare).filter(containsRule(_)).map(_._1.price).sum
  }

  def main(args: Array[String]): Unit = {
    FareModel.fromDirectory(Paths.get(args(0)))

    println(calcFare("CE", "1", "55448", "55449"))
    println(calcFare("CE", "ACE", "55448", "55449"))
    println(calcFare("CE", "ACE", "55448", "55450"))
    println(calcFare("CE", null, "55448", "55643"))
    println(calcFare(null, null, "55448", "55643"))
//    println(calcFare("CE", "ACE", null, null, Vector("55448", "55449", "55645")))
  }
}
