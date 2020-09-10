package beam.utils.google_routes_db

import beam.utils.FileUtils.using
import java.sql.{Connection, Statement}

import beam.utils.google_routes_db.sql.PSMapping
import com.typesafe.scalalogging.LazyLogging

object GoogleRoutesDB extends LazyLogging {

  //
  // Updates
  //

  type InsertedGoogleRouteId = Int
  type InsertedGoogleRouteLegId = Int

  def createGoogleRoutesTables(con: Connection): Unit = {
    using(con.prepareStatement(sql.DDL.googleRouteTable)) { ps => ps.execute() }
    using(con.prepareStatement(sql.DDL.googleRouteLegTable)) { ps => ps.execute() }
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
}
