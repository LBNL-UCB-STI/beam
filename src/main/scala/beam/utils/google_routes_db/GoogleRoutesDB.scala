package beam.utils.google_routes_db

import java.sql.{Connection, Statement}
import java.time.{Instant, LocalDateTime}

import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.events.handling.GoogleTravelTimeEstimationEntry
import beam.sim.common.GeoUtils
import beam.utils.FileUtils.using
import beam.utils.google_routes_db.sql.{PSMapping, Update}
import beam.utils.mapsapi.googleapi.GoogleRoutesResponse
import com.google.maps.model.DirectionsRoute
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable

object GoogleRoutesDB extends LazyLogging {

  //
  // Queries
  //

  def queryGoogleRoutes(
    ptes: TraversableOnce[PathTraversalEvent],
    departureDateTime: LocalDateTime,
    geoUtils: GeoUtils,
    con: Connection
  ): Map[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]] = {
    using(con.prepareStatement(sql.Select.QueryGoogleRoutes.sql)) { ps =>
      val result = mutable.Map.empty[PathTraversalEvent, Seq[GoogleTravelTimeEstimationEntry]]

      ptes.foreach { pte =>
        import sql.Select.QueryGoogleRoutes._

        val arg = makeArg(pte, departureDateTime)
        argPsMapping.mapPrepared(arg, ps)
        val items = using(ps.executeQuery()) { rs => readItems(rs) }

        // taking first result
        val gttees: Seq[GoogleTravelTimeEstimationEntry] = items.map { item =>
          GoogleTravelTimeEstimationEntry.fromPTE(
            pte,
            item.requestId,
            item.duration,
            item.durationInTraffic,
            item.distance,
            geoUtils
          )
        }
        result += pte -> gttees
      }

      result.toMap
    }
  }

  //
  // Updates
  //

  type InsertedGoogleRouteId = Int
  type InsertedGoogleRouteLegId = Int

  def createGoogleRoutesTables(con: Connection): Unit = {
    using(con.prepareStatement(sql.DDL.googleRouteTable)) { ps => ps.execute() }
    using(con.prepareStatement(sql.DDL.googleRouteLegTable)) { ps => ps.execute() }
    using(con.prepareStatement(sql.DDL.googleRouteLegStartLocationIdx)) { ps => ps.execute() }
    using(con.prepareStatement(sql.DDL.googleRouteLegEndLocationIdx)) { ps => ps.execute() }
  }

  def insertGoogleRoutesAndLegs(
    grrSeq: Seq[GoogleRoutesResponse],
    requestIdToDepartureTime: Map[String, Int],
    googleapiResponsesJsonFileUri: String,
    timestamp: Instant,
    con: Connection
  ): Unit = {
    grrSeq.foreach { grr: GoogleRoutesResponse =>

      val routeItemToRoute: Map[Update.GoogleRouteItem, DirectionsRoute] =
        grr.directionsResult.routes.map { route =>
          val item = sql.Update.GoogleRouteItem.create(
            route = route,
            requestId = grr.requestId,
            departureDateTime = LocalDateTime.parse(grr.departureLocalDateTime),
            departureTime = requestIdToDepartureTime(grr.requestId),
            googleapiResponsesJsonFileUri = Some(googleapiResponsesJsonFileUri),
            timestamp = timestamp
          )
          (item, route)
        }.toMap

      val routeItemToId: Map[Update.GoogleRouteItem, InsertedGoogleRouteId] =
        insertGoogleRoutes(routeItemToRoute.keys.toSeq, con)

      val legItems: Seq[Update.GoogleRouteLegItem] =
        routeItemToId.flatMap { case (routeItem, routeId) =>
          routeItemToRoute(routeItem).legs.map { leg =>
            sql.Update.GoogleRouteLegItem.create(routeId, leg)
          }
        }.toSeq

      insertGoogleRouteLegs(legItems, con)
    }
  }

  def insertGoogleRoutes(
    items: Seq[sql.Update.GoogleRouteItem],
    con: Connection
  ): Map[sql.Update.GoogleRouteItem, InsertedGoogleRouteId] = {
    Update.GoogleRouteItem.insert(items, con)
  }

  def insertGoogleRouteLegs(
    items: Seq[sql.Update.GoogleRouteLegItem],
    con: Connection
  ): Map[sql.Update.GoogleRouteLegItem, InsertedGoogleRouteLegId] = {
    Update.GoogleRouteLegItem.insert(items, con)
  }
}
