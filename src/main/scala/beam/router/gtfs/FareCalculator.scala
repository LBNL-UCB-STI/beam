package beam.router.gtfs

import java.io._
import java.nio.file.{Files, Path, Paths}
import java.util.zip.ZipFile

import beam.router.gtfs.FareCalculator._
import com.conveyal.gtfs.GTFSFeed

class FareCalculator(val directory: String) {

  private val dataDirectory: Path = Paths.get(directory)
  private val cacheFile: File = dataDirectory.resolve("fares.dat").toFile

  /**
    * agencies is a Map of FareRule by agencyId
    */
  val agencies: Map[String, Vector[BeamFareRule]] = if (cacheFile.exists()) {
    new ObjectInputStream(new FileInputStream(cacheFile)).readObject().asInstanceOf[Map[String, Vector[BeamFareRule]]]
  } else {
    val agencies = fromDirectory(dataDirectory)
    val stream = new ObjectOutputStream(new FileOutputStream(cacheFile))
    stream.writeObject(agencies)
    stream.close()
    agencies
  }

  /**
    * Use to initialize the calculator by loading GTFS feeds and populates agencies map.
    *
    * @param directory Path of the directory that contains gtfs files to load
    */
  def fromDirectory(directory: Path): Map[String, Vector[BeamFareRule]] = {

    var agencies: Map[String, Vector[BeamFareRule]] = Map()

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
      var fares: Map[String, BeamFare] = Map()
      var routes: Map[String, Vector[BeamFareRule]] = Map()
      var agencyRules: Vector[BeamFareRule] = Vector()
      val agencyId = feed.agency.values().stream().findFirst().get().agency_id

      feed.fares.forEach((id, fare) => {
        val attr = fare.fare_attribute
        fares += (id -> BeamFare(attr.fare_id, attr.price, attr.currency_type, attr.payment_method, if (attr.transfer_duration > 0 && attr.transfers == 0) Int.MaxValue else attr.transfers, attr.transfer_duration))

        fare.fare_rules.forEach(r => {

          val rule: BeamFareRule = BeamFareRule(fares.get(r.fare_id).head, agencyId, r.route_id, r.origin_id, r.destination_id, r.contains_id)

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

    if (Files.isDirectory(directory)) {
      directory.toFile.listFiles(hasFares(_)).map(_.getAbsolutePath).foreach(p => {
        val feed = GTFSFeed.fromFile(p)
        loadFares(feed)
        feed.close()
      })
    }
    agencies
  }

  def getFareSegments(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Vector[BeamFareSegment] = {
    val _containsIds = if (containsIds == null || containsIds.isEmpty) Set(fromId, toId) else containsIds

    val rules = agencies.getOrElse(agencyId, Vector()).partition(_.containsId == null)

    (rules._1.filter(baseRule(_, routeId, fromId, toId)) ++
      rules._2.groupBy(_.fare).filter(containsRule(_, routeId, _containsIds)).map(_._2.last)).map(f => BeamFareSegment(f.fare, agencyId))
  }

  def calcFare(agencyId: String, routeId: String, fromId: String, toId: String, containsIds: Set[String] = null): Double = {
    sumFares(getFareSegments(agencyId, routeId, fromId, toId, containsIds))
  }

  def sumFares(rules: Vector[BeamFareSegment]): Double = {
    val f = filterTransferFares(rules)
    f.map(_.fare.price).sum
  }

}

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
  case class BeamFare(fareId: String,
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
  case class BeamFareRule(fare: BeamFare,
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
  case class BeamFareSegment(fare: BeamFare,
                             agencyId: String,
                             patternIndex: Int,
                             segmentDuration: Long)

  object BeamFareSegment {
    def apply(fare: BeamFare, agencyId: String): BeamFareSegment = new BeamFareSegment(fare, agencyId, 0, 0)
    def apply(fareSegment: BeamFareSegment, patternIndex: Int, segmentDuration: Long): BeamFareSegment = new BeamFareSegment(fareSegment.fare, fareSegment.agencyId, patternIndex, segmentDuration)
  }


  //  lazy val containRules = agencies.map(a => a._1 -> a._2.filter(r => r.containsId != null).groupBy(_.fare))

  // Fare depends on which route the itinerary uses AND Fare depends on origin or destination stations
  // BUT Fare depends on which zones the itinerary passes through, is group rule and apply separately
  private def baseRule(r: BeamFareRule, routeId: String, fromId: String, toId: String): Boolean =
  (r.routeId == routeId || r.routeId == null) &&
    (r.originId == fromId || r.originId == null) &&
    (r.destinationId == toId || r.destinationId == null)

  //Fare depends on which zones the itinerary passes through
  private def containsRule(t: (BeamFare, Vector[BeamFareRule]), routeId: String, containsIds: Set[String]) =
    t._2.map(_.routeId).distinct.forall(id => id == routeId || id == null) &&
      t._2.map(_.containsId).toSet.equals(containsIds)

  def filterTransferFares(rules: Vector[BeamFareSegment]): Vector[BeamFareSegment] = {
    def iterateTransfers(rules: Vector[BeamFareSegment], trans: Int = 0): Vector[BeamFareSegment] = {
      def next: Int = if (trans == Int.MaxValue) 0 else trans match {
        case 0 | 1 => trans + 1
        case 2 => Int.MaxValue
        case _ => 0
      }

      def applyTransfer(lhs: Vector[BeamFareSegment]): Vector[BeamFareSegment] = {
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

    def spanAgency(rules: Vector[BeamFareSegment]): Vector[BeamFareSegment] = {
      if (rules.isEmpty)
        Vector()
      else {
        val agencyRules = rules.span(_.agencyId == rules.head.agencyId)
        iterateTransfers(agencyRules._1) ++ spanAgency(agencyRules._2)
      }
    }

    spanAgency(rules)
  }

}
