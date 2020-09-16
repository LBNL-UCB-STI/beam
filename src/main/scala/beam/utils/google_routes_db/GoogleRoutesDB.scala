package beam.utils.google_routes_db

import beam.utils.FileUtils.using
import java.sql.{Connection, Statement}
import java.time.{Instant, LocalDateTime}

import beam.agentsim.events.PathTraversalEvent
import beam.agentsim.events.handling.GoogleTravelTimeEstimationEntry
import beam.sim.common.GeoUtils
import beam.utils.google_routes_db.sql.{PSMapping, Update}
import beam.utils.mapsapi.googleapi.route.{GoogleRoute, GoogleRoutesResponse}
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

      val grItemToGr: Map[Update.GoogleRouteItem, GoogleRoute] =
        createGoogleRouteItems(
          grr.response.routes,
          grr.requestId,
          LocalDateTime.parse(grr.departureLocalDateTime),
          requestIdToDepartureTime(grr.requestId),
          googleapiResponsesJsonFileUri,
          timestamp
        )

      val grItemToId: Map[Update.GoogleRouteItem, InsertedGoogleRouteId] =
        GoogleRoutesDB.insertGoogleRoutes(grItemToGr.keys.toSeq, con)

      GoogleRoutesDB.insertGoogleRouteLegs(
        createGoogleRouteLegItems(
          grItemToId.flatMap { case (grItem, routeId) =>
            grItemToGr(grItem).legs.map(leg => (routeId, leg))
          }.toSeq
        ),
        con
      )
    }
  }

  def insertGoogleRoutes(
    items: Seq[sql.Update.GoogleRouteItem],
    con: Connection
  ): Map[sql.Update.GoogleRouteItem, InsertedGoogleRouteId] = {
    import sql.Update.GoogleRouteItem.psMapping
    insertMappableBatch(items, sql.Update.GoogleRouteItem.insertSql, con)
  }

  def insertGoogleRouteLegs(
    items: Seq[sql.Update.GoogleRouteLegItem],
    con: Connection
  ): Map[sql.Update.GoogleRouteLegItem, InsertedGoogleRouteLegId] = {
    import sql.Update.GoogleRouteLegItem.psMapping
    insertMappableBatch(items, sql.Update.GoogleRouteLegItem.insertSql, con)
  }

  private def insertMappableBatch[T : PSMapping](
    items: Seq[T],
    sql: String,
    con: Connection
  ): Map[T, Int] = {
    using(
      con.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS)
    ) { ps =>
      items.foreach { item =>
        implicitly[PSMapping[T]].mapPrepared(item, ps)
        ps.addBatch()
      }
      ps.executeBatch()

      val keysRS = ps.getGeneratedKeys

      items.map { item =>
        keysRS.next()
        (item, keysRS.getInt(1))
      }.toMap
    }
  }

  private def createGoogleRouteItems(
    grs: Seq[GoogleRoute],
    requestId: String,
    departureDateTime: LocalDateTime,
    departureTime: Int,
    googleapiResponsesJsonFileUri: String,
    timestamp: Instant,
  ): Map[sql.Update.GoogleRouteItem, GoogleRoute] = {
    grs.map { gr =>
      val item = sql.Update.GoogleRouteItem.create(
        googleRoute = gr,
        requestId = requestId,
        departureDateTime = departureDateTime,
        departureTime = departureTime,
        googleapiResponsesJsonFileUri = Some(googleapiResponsesJsonFileUri),
        timestamp = timestamp
      )
      (item, gr)
    }.toMap
  }

  private def createGoogleRouteLegItems(
    routeIdLegs: Seq[(InsertedGoogleRouteId, GoogleRoute.Leg)]
  ): Seq[sql.Update.GoogleRouteLegItem] = {
    routeIdLegs.map { case (routeId, leg) =>
      sql.Update.GoogleRouteLegItem.create(routeId, leg)
    }
  }
}
