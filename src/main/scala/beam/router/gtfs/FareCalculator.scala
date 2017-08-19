package beam.router.gtfs

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util.zip.ZipFile

import beam.router.r5.R5RoutingWorker.transportNetwork
import com.conveyal.gtfs.GTFSFeed
import com.conveyal.r5.api.util.{Stop, TransitJourneyID, TransitSegment}
import com.conveyal.r5.transit.RouteInfo
import org.slf4j.{Logger, LoggerFactory}

class FareCalculator

object FareCalculator {
  case class FareRule(fare: Fare, agencyId: String, routeId: String, originId: String, destinationId: String, containsId: String)
  case class Fare(fareId: String, price: Double, currencyType: String, paymentMethod: Int, transfers: Int, transferDuration: Int)

  var agencies: Map[String, Vector[FareRule]] = null

  //  lazy val containRules = agencies.map(a => a._1 -> a._2.filter(r => r.containsId != null).groupBy(_.fare))


  def fromDirectory(directory: Path): Unit = {
    agencies = Map()

    if (Files.isDirectory(directory)) {
      directory.toFile.listFiles(hasFares(_)).map(_.getAbsolutePath).foreach(p => {
        loadFares(GTFSFeed.fromFile(p))
      })
    }

    def hasFares(file: File): Boolean = {
      var isFareExist = false
      if (file.getName.endsWith(".zip")) {
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
      val agencyId = feed.agency.values().stream().findFirst().get().agency_id

      feed.fares.forEach((id, fare) => {
        val attr = fare.fare_attribute
        fares += (id -> Fare(attr.fare_id, attr.price, attr.currency_type, attr.payment_method, if (attr.transfer_duration > 0 && attr.transfers == 0) Int.MaxValue else attr.transfers, attr.transfer_duration))

        fare.fare_rules.forEach(r => {

          val rule: FareRule = FareRule(fares.get(r.fare_id).head, agencyId, r.route_id, r.origin_id, r.destination_id, r.contains_id)

          if (r.route_id == null) {
            agencyRules = agencyRules :+ rule
          } else {

            var rules = routes.getOrElse(r.route_id, Vector())
            rules = rules :+ rule
            routes += (r.route_id -> rules)
          }
        })
      })


      feed.agency.forEach((id, _) => {
        feed.routes.values().stream().filter(_.agency_id == id).forEach(route => {
          agencyRules ++= routes.getOrElse(route.route_id, Vector())
        })
        agencies += id -> agencyRules
      })
    }
  }

  def calcFare(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Double = {
    sumFares(getFareRules(agencyId, routeId, fromId, toId, containsIds))
  }

  def calcFare(segments: Vector[(TransitSegment, TransitJourneyID)]): Double = {
    sumFares(getFareRules(segments))
  }

  def getFareRules(segments: Vector[(TransitSegment, TransitJourneyID)]): Vector[FareRule] = {
    segments.groupBy(s => transportNetwork.transitLayer.routes.get(s._1.segmentPatterns.get(s._2.pattern).routeIndex).agency_id).map(t => {

      val route = transportNetwork.transitLayer.routes.get(t._2.head._1.segmentPatterns.get(t._2.head._2.pattern).routeIndex)
      val agencyId = route.agency_id
      val routeId = route.route_id

      val fromId = getStopId(t._2.head._1.from)
      val toId = getStopId(t._2.last._1.to)

      val containsIds = t._2.map(s => Vector(getStopId(s._1.from), getStopId(s._1.to))).flatten.toSet

      var rules = getFareRules(agencyId, routeId, fromId, toId, containsIds)

      if (rules.isEmpty) {
        rules = t._2.map(s => getFareRules(route, s._1)).flatten
      }
      rules
    }).flatten.toVector
  }

  def calcFare(route: RouteInfo, transitSegment: TransitSegment): Double = {
    sumFares(getFareRules(route, transitSegment))
  }

  def getFareRules(route: RouteInfo, transitSegment: TransitSegment): Vector[FareRule] = {
    val routeId = route.route_id
    val agencyId = route.agency_id

    val fromStopId = getStopId(transitSegment.from)
    val toStopId = getStopId(transitSegment.to)

    var fr = getFareRules(agencyId, routeId, fromStopId, toStopId)
    if (!fr.isEmpty)
      fr = Vector(getFareRules(agencyId, routeId, fromStopId, toStopId).minBy(_.fare.price))
    fr
  }

  def getFareRules(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Vector[FareRule] = {
    val _containsIds = if (containsIds == null || containsIds.isEmpty) Set(fromId, toId) else containsIds

    val rules = agencies.getOrElse(agencyId, Vector()).partition(_.containsId == null)

    rules._1.filter(baseRule(_, routeId, fromId, toId)) ++
      rules._2.groupBy(_.fare).filter(containsRule(_, routeId, _containsIds)).map(_._2.last)
  }

  // Fare depends on which route the itinerary uses AND Fare depends on origin or destination stations
  // BUT Fare depends on which zones the itinerary passes through, is group rule and apply separately
  def baseRule(r: FareRule, routeId: String, fromId: String, toId: String) =
  (r.routeId == routeId || r.routeId == null) &&
    (r.originId == fromId || r.originId == null) &&
    (r.destinationId == toId || r.destinationId == null)

  //Fare depends on which zones the itinerary passes through
  private def containsRule(t: (Fare, Vector[FareRule]), routeId: String, containsIds: Set[String]) =
    t._2.map(_.routeId).distinct.forall(id => id == routeId || id == null) &&
      t._2.map(_.containsId).toSet.equals(containsIds)

  private def getStopId(stop: Stop) = stop.stopId.split((":"))(1)

  private def sumFares(rules: Vector[FareRule]): Double = {
    /*def sum(rules: Vector[FareRule], trans: Int = 0): Double = {
      def next: Int = if (trans == Int.MaxValue) 0 else trans match {
        case 0 | 1 => trans + 1
        case 2 => Int.MaxValue
        case _ => 0
      }

      def internalSum(lhs: Vector[FareRule]) = {
        trans match {
          case 0 => lhs.map(_.fare.price).sum
          case 1 | 2 => lhs.zipWithIndex.filter(_._2 % trans + 1 == 0).map(_._1.fare.price).sum
          case _ => lhs.map(_.fare.price).head
        }
      }

      rules.span(_.fare.transfers == trans) match {
        case (Vector(), Vector()) => 0.0
        case (Vector(), rhs) => sum(rhs, next)
        case (lhs, Vector()) => internalSum(lhs)
        case (lhs, rhs) => internalSum(lhs) + trans match {
          case 0 => sum(rhs, next)
          case 1 | 2 => sum(rhs.splitAt(lhs.size % trans + 1)._2, next)
          case _ => 0.0
        }
      }
    }

    val agencyRules = rules.span(_.agencyId == rules.head.agencyId)
    sum(agencyRules._1) + sum(agencyRules._2)*/
    val f = filterTransferFares(rules)
    f.map(_.fare.price).sum
  }

  /*def calcFare(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Double = {
    val _containsIds = if(containsIds == null || containsIds.isEmpty) Set(fromId, toId) else containsIds

    val rules = agencies.getOrElse(agencyId, Vector()).partition(_.containsId == null)

    rules._1.withFilter(baseRule(_, routeId, fromId, toId)).map(_.fare.price).sum +
      rules._2.groupBy(_.fare).filter(containsRule(_, routeId, _containsIds)).map(_._1.price).sum
  }*/

  def filterTransferFares(rules: Vector[FareRule]): Vector[FareRule] = {
    def iterateTransfers(rules: Vector[FareRule], trans: Int = 0): Vector[FareRule] = {
      def next: Int = if (trans == Int.MaxValue) 0 else trans match {
        case 0 | 1 => trans + 1
        case 2 => Int.MaxValue
        case _ => 0
      }

      def applyTransfer(lhs: Vector[FareRule]): Vector[FareRule] = {
        trans match {
          case 0 => lhs
          case 1 | 2 => lhs.zipWithIndex.filter(t => (t._2 + 1) % (trans + 1) == 1).map(_._1)
          case _ => Vector(lhs.head)
        }
      }

      rules.span(_.fare.transfers == trans) match {
        case (Vector(), Vector()) => Vector()
        case (Vector(), rhs) => iterateTransfers(rhs, next)
        case (lhs, Vector()) => applyTransfer(lhs)
        case (lhs, rhs) => applyTransfer(lhs) ++ (trans match {
          case 0 => iterateTransfers(rhs, next)
          case 1 | 2 => iterateTransfers(rhs.splitAt((trans + 1) - lhs.size % (trans + 1))._2, trans)
          case _ => Vector()
        })
      }
    }

    def spanAgency(rules: Vector[FareRule]): Vector[FareRule] = {
      if (rules.isEmpty)
        Vector()
      else {
        val agencyRules = rules.span(_.agencyId == rules.head.agencyId)
        iterateTransfers(agencyRules._1) ++ spanAgency(agencyRules._2)
      }
    }

    spanAgency(rules)
  }

  def main(args: Array[String]): Unit = {
    fromDirectory(Paths.get(args(0)))

    println(calcFare("CE", "1", "55448", "55449"))
    println(calcFare("CE", "ACE", "55448", "55449"))
    println(calcFare("CE", "ACE", "55448", "55450"))
    println(calcFare("CE", null, "55448", "55643"))
    println(calcFare(null, null, "55448", "55643"))
    println(calcFare("CE", "ACE", null, null, Set("55448", "55449", "55643")))
    println(calcFare("CE", "ACE", "55643", "55644"))
    println(calcFare("CE", "ACE", "55644", "55645"))
    println(calcFare("CE", "ACE", "55645", "55645"))

    val fr = getFareRules("CE", "ACE", null, null, Set("55448", "55449", "55643")) ++
      getFareRules("CE", "ACE", "55643", "55644") ++
      getFareRules("CE", "ACE", "55644", "55645") ++
      getFareRules("CE", "ACE", "55645", "55645")
    println(sumFares(fr))
  }
}
