package beam.router.gtfs

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.time.ZonedDateTime
import java.time.temporal.ChronoUnit
import java.util.zip.ZipFile

import beam.router.r5.NetworkCoordinator.transportNetwork
import com.conveyal.gtfs.GTFSFeed
import com.conveyal.r5.api.util.{SegmentPattern, Stop, TransitJourneyID, TransitSegment}


object FareCalculator {

  /**
    * A FareAttribute (defined in fare_attributes.txt) defines a fare class. A FareAttribute has a price,
    * currency and whether it must be purchased on board the service or before boarding.
    * It also defines the number of transfers it can be used for, and the duration it is valid.
    *
    * @param fareId Contains an ID that uniquely identifies a fare class. The fare_id is dataset unique. Its a required attribute.
    * @param price Contains the fare price, in the unit specified by currency_type. Its a required attribute.
    * @param currencyType Defines the currency used to pay the fare. Its a required attribute.
    * @param paymentMethod The payment_method field indicates when the fare must be paid. Its a required attribute. Valid values for this field are:
    *                      0: Fare is paid on board.
    *                      1: Fare must be paid before boarding.
    *
    * @param transfers Specifies the number of transfers permitted on this fare. Its a required attribute. Valid values for this field are:
    *                  0: No transfers permitted on this fare.
    *                  1: Passenger may transfer once.
    *                  2: Passenger may transfer twice.
    *                  Int.MaxValue/(empty in gtfs): If this field is empty, unlimited transfers are permitted.
    *
    * @param transferDuration Specifies the length of time in seconds before a transfer expires.
    */
  case class Fare(fareId: String,
                  price: Double,
                  currencyType: String,
                  paymentMethod: Int,
                  transfers: Int,
                  transferDuration: Int)

  /**
    * The FareRule lets you specify how fares in fare_attributes.txt apply to an itinerary.
    * Most fare structures use some combination of the following rules:
    *   Fare depends on origin or destination stations.
    *   Fare depends on which zones the itinerary passes through.
    *   Fare depends on which route the itinerary uses.
    *
    * @param fare Contains a fare object from fare_attributes.
    * @param agencyId Defines an agency for the specified route. This value is referenced from the agency.txt file.
    * @param routeId Associates the fare ID with a route. Route IDs are referenced from the routes.txt file.
    * @param originId Associates the fare ID with an origin zone ID (referenced from the stops.txt file).
    * @param destinationId Associates the fare ID with a destination zone ID (referenced from the stops.txt file).
    * @param containsId Associates the fare ID with a zone ID (referenced from the stops.txt file.
    *                   The fare ID is then associated with itineraries that pass through every contains_id zone.
    */
  case class FareRule(fare: Fare,
                      agencyId: String,
                      routeId: String,
                      originId: String,
                      destinationId: String,
                      containsId: String)

  /**
    *
    * @param fare Contains a fare object from fare_attributes.
    * @param agencyId Defines an agency for the specified route. This value is referenced from the agency.txt file.
    * @param patternIndex Represents the pattern index from TransitJournyID to locate SegmentPattern from a specific TransitSegment
    * @param segmentDuration Defines the leg duration from start of itinerary to end of segment leg
    */
  case class FareSegment(fare: Fare,
                         agencyId: String,
                         patternIndex: Int,
                         segmentDuration: Long)

  object FareSegment {
    def apply(fare: Fare, agencyId: String): FareSegment = new FareSegment(fare, agencyId, 0, 0)
    def apply(fareSegment: FareSegment, patternIndex: Int, segmentDuration: Long): FareSegment = new FareSegment(fareSegment.fare, fareSegment.agencyId, patternIndex, segmentDuration)
  }

  /**
    * agencies is a Map of FareRule by agencyId
    */
  var agencies: Map[String, Vector[FareRule]] = _

  //  lazy val containRules = agencies.map(a => a._1 -> a._2.filter(r => r.containsId != null).groupBy(_.fare))

  /**
    * Use to initialize the calculator by loading GTFS feeds and populates agencies map.
    *
    * @param directory Path of the directory that contains gtfs files to load
    */
  def fromDirectory(directory: Path): Unit = {
    /**
      * Checks whether its a valid gtfs feed and has fares data.
      *
      * @param file specific file to check.
      * @return true if a valid zip having fare data.
      */
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

    /**
      * Takes GTFSFeed and loads agencies map with fare and its rules.
      *
      * @param feed GTFSFeed
      */
    def loadFares(feed: GTFSFeed): Unit = {
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

    agencies = Map()

    if (Files.isDirectory(directory)) {
      directory.toFile.listFiles(hasFares(_)).map(_.getAbsolutePath).foreach(p => {
        loadFares(GTFSFeed.fromFile(p))
      })
    }
  }

  /**
    * Use to extract a collection of FareSegments for an itinerary.
    *
    * @param segments
    * @return a collection of FareSegments for an itinerary.
    */
  def getFareSegments(segments: Vector[(TransitSegment, TransitJourneyID)]): Vector[FareSegment] = {
    segments.groupBy(s => getRoute(s._1, s._2).agency_id).flatMap(t => {
      val pattern = getPattern(t._2.head._1, t._2.head._2)
      val route = getRoute(pattern)
      val agencyId = route.agency_id
      val routeId = route.route_id

      val fromId = getStopId(t._2.head._1.from)
      val toId = getStopId(t._2.last._1.to)

      val fromTime = pattern.fromDepartureTime.get(t._2.head._2.time)
      val toTime = getPattern(t._2.last._1, t._2.last._2).toArrivalTime.get(t._2.last._2.time)
      val duration = ChronoUnit.SECONDS.between(fromTime, toTime)


      val containsIds = t._2.flatMap(s => Vector(getStopId(s._1.from), getStopId(s._1.to))).toSet

      var rules = getFareSegments(agencyId, routeId, fromId, toId, containsIds).map(f => FareSegment(f, t._2.head._2.pattern, duration))

      if (rules.isEmpty)
        rules = t._2.flatMap(s => getFareSegments(s._1, s._2, fromTime))

      rules
    }).toVector
  }

  def getFareSegments(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID, fromTime: ZonedDateTime): Vector[FareSegment] = {
    val pattern = getPattern(transitSegment, transitJourneyID)
    val route = getRoute(pattern)
    val routeId = route.route_id
    val agencyId = route.agency_id

    val fromStopId = getStopId(transitSegment.from)
    val toStopId = getStopId(transitSegment.to)
    val duration = ChronoUnit.SECONDS.between(fromTime, pattern.toArrivalTime.get(transitJourneyID.time))

    var fr = getFareSegments(agencyId, routeId, fromStopId, toStopId).map(f => FareSegment(f, transitJourneyID.pattern, duration))
    if (fr.nonEmpty)
      fr = Vector(fr.minBy(_.fare.price))
    fr
  }

  def getFareSegments(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Vector[FareSegment] = {
    val _containsIds = if (containsIds == null || containsIds.isEmpty) Set(fromId, toId) else containsIds

    val rules = agencies.getOrElse(agencyId, Vector()).partition(_.containsId == null)

    (rules._1.filter(baseRule(_, routeId, fromId, toId)) ++
      rules._2.groupBy(_.fare).filter(containsRule(_, routeId, _containsIds)).map(_._2.last)).map(f => FareSegment(f.fare, agencyId))
  }

  def filterTransferFares(rules: Vector[FareSegment]): Vector[FareSegment] = {
    def iterateTransfers(rules: Vector[FareSegment], trans: Int = 0): Vector[FareSegment] = {
      def next: Int = if (trans == Int.MaxValue) 0 else trans match {
        case 0 | 1 => trans + 1
        case 2 => Int.MaxValue
        case _ => 0
      }

      def applyTransfer(lhs: Vector[FareSegment]): Vector[FareSegment] = {
        trans match {
          case 0 => lhs
          case 1 | 2 => lhs.zipWithIndex.filter(t => (t._2 + 1) % (trans + 1) == 1 || t._1.segmentDuration > t._1.fare.transferDuration).map(_._1)
          case _ => lhs.zipWithIndex.filter(t => t._2 == 0 || t._1.segmentDuration > t._1.fare.transferDuration).map(_._1)
        }
      }

      rules.span(_.fare.transfers == trans) match {
        case (Vector(), Vector()) => Vector()
        case (Vector(), rhs) => iterateTransfers(rhs, next)
        case (lhs, Vector()) => applyTransfer(lhs)
        case (lhs, rhs) => applyTransfer(lhs) ++ (trans match {
          case 0 => iterateTransfers(rhs, next)
          case 1 | 2 => val sf = rhs.span(t => t.segmentDuration <= lhs(lhs.size - (lhs.size % (trans + 1))) .fare.transferDuration); iterateTransfers(sf._1.splitAt((trans + 1) - lhs.size % (trans + 1))._2 ++ sf._2, trans)
          case _ => rhs.span(t => t.segmentDuration <= lhs.head.fare.transferDuration)._2
        })
      }
    }

    def spanAgency(rules: Vector[FareSegment]): Vector[FareSegment] = {
      if (rules.isEmpty)
        Vector()
      else {
        val agencyRules = rules.span(_.agencyId == rules.head.agencyId)
        iterateTransfers(agencyRules._1) ++ spanAgency(agencyRules._2)
      }
    }

    spanAgency(rules)
  }

  def calcFare(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Double = {
    sumFares(getFareSegments(agencyId, routeId, fromId, toId, containsIds))
  }

  def calcFare(segments: Vector[(TransitSegment, TransitJourneyID)]): Double = {
    sumFares(getFareSegments(segments))
  }

  def calcFare(transitSegment: TransitSegment, journeyId: TransitJourneyID, fromTime: ZonedDateTime): Double = {
    sumFares(getFareSegments(transitSegment, journeyId, fromTime))
  }

  // Fare depends on which route the itinerary uses AND Fare depends on origin or destination stations
  // BUT Fare depends on which zones the itinerary passes through, is group rule and apply separately
  private def baseRule(r: FareRule, routeId: String, fromId: String, toId: String): Boolean =
  (r.routeId == routeId || r.routeId == null) &&
    (r.originId == fromId || r.originId == null) &&
    (r.destinationId == toId || r.destinationId == null)

  //Fare depends on which zones the itinerary passes through
  private def containsRule(t: (Fare, Vector[FareRule]), routeId: String, containsIds: Set[String]) =
    t._2.map(_.routeId).distinct.forall(id => id == routeId || id == null) &&
      t._2.map(_.containsId).toSet.equals(containsIds)


  private def getRoute(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transportNetwork.transitLayer.routes.get(getPattern(transitSegment, transitJourneyID).routeIndex)

  private def getRoute(segmentPattern: SegmentPattern) =
    transportNetwork.transitLayer.routes.get(segmentPattern.routeIndex)

  private def getPattern(transitSegment: TransitSegment, transitJourneyID: TransitJourneyID) =
    transitSegment.segmentPatterns.get(transitJourneyID.pattern)

  private def getStopId(stop: Stop) = stop.stopId.split(":")(1)

  def sumFares(rules: Vector[FareSegment]): Double = {
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

  def main(args: Array[String]): Unit = {
    fromDirectory(Paths.get(args(0)))

    println(calcFare("CE", "1", "55448", "55449")) // 0.0
    println(calcFare("CE", "ACE", "55448", "55449")) //4.5
    println(calcFare("CE", "ACE", "55448", "55450")) //5.5
    println(calcFare("CE", null, "55448", "55643")) //9.5
    println(calcFare(null, null, "55448", "55643")) //0.0
    println(sumFares(getFareSegments("CE", "ACE", null, null, Set("55448", "55449", "55643")).map(FareSegment(_, 0, 3200)))) //13.75
    println(calcFare("CE", "ACE", "55643", "55644")) // 5.25
    println(calcFare("CE", "ACE", "55644", "55645")) //5.25
    println(calcFare("CE", "ACE", "55645", "55645")) //4.0

    val fr = getFareSegments("CE", "ACE", null, null, Set("55448", "55449", "55643")).map(FareSegment(_, 0, 3200)) ++
      getFareSegments("CE", "ACE", "55643", "55644").map(FareSegment(_, 0, 3800)) ++
      getFareSegments("CE", "ACE", "55644", "55645").map(FareSegment(_, 0, 4300)) ++
      getFareSegments("CE", "ACE", "55645", "55645").map(FareSegment(_, 0, 4700))
    println(sumFares(fr)) // 17.75
  }
}
