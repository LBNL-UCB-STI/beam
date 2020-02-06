package census.db.creator.database
import java.sql.ResultSet

import census.db.creator.GeometryUtil._
import census.db.creator.config.{Config, Hardcoded}
import census.db.creator.domain.TazInfo
import com.vividsolutions.jts.geom.Polygon
import com.vividsolutions.jts.geom.prep.PreparedGeometryFactory
import org.skife.jdbi.v2.{DBI, StatementContext}

import scala.collection.JavaConverters._

trait TazRepository extends AutoCloseable {
  private[creator] def save(data: Seq[TazInfo])

  def query(
    geoId: Option[String] = None,
    stateFp: Option[String] = None,
    countyFp: Option[String] = None,
    tractCode: Option[String] = None,
    border: Option[Polygon] = None
  ): Seq[TazInfo]
}

class PostgresTazRepo(private val config: Config = Hardcoded.config) extends TazRepository {
  private val dataTable = "taz_info"

  private val connection = new DBI(config.db.url, config.db.user, config.db.password).open()

  private[creator] override def save(data: Seq[TazInfo]): Unit = {
    val batch = connection.prepareBatch(
      s"""insert into $dataTable (geo_id, state_fp, county_fp, tract_code, land_area, water_area, geometry)
        values(:geo_id, :state_fp, :county_fp, :tract_code, :land_area, :water_area, st_geomfromtext(:geometry,$projection))"""
    )

    data.foreach { x =>
      batch
        .bind("geo_id", x.geoId)
        .bind("state_fp", x.stateFp)
        .bind("county_fp", x.countyFp)
        .bind("tract_code", x.tractCode)
        .bind("land_area", x.landArea)
        .bind("water_area", x.waterArea)
        .bind("geometry", x.preparedGeometry.getGeometry.toText)
        .add()
    }
    batch.execute()
  }

  def query(
    geoId: Option[String],
    stateFp: Option[String],
    countyFp: Option[String],
    tractCode: Option[String],
    border: Option[Polygon]
  ): Seq[TazInfo] = {
    var sql = s"""
        select *, st_asbinary(geometry) as geom from $dataTable
        where 1=1"""

    if (geoId.isDefined) {
      sql = sql + " AND geo_id = :geo_id"
    }
    if (stateFp.isDefined) {
      sql = sql + " AND state_fp = :state_fp"
    }
    if (countyFp.isDefined) {
      sql = sql + " AND county_fp = :county_fp"
    }
    if (tractCode.isDefined) {
      sql = sql + " AND tract_code = :tract_code"
    }
    if (border.isDefined) {
      sql = sql + s" AND st_intersects(geometry, st_geomfromtext(:geometry, $projection))"
    }
    val query = connection.createQuery(sql)
    if (geoId.isDefined) {
      query.bind("geo_id", geoId.get)
    }
    if (stateFp.isDefined) {
      query.bind("state_fp", stateFp.get)
    }
    if (countyFp.isDefined) {
      query.bind("county_fp", countyFp.get)
    }
    if (tractCode.isDefined) {
      query.bind("tract_code", tractCode.get)
    }
    if (border.isDefined) {
      query.bind("geometry", border.get.toText)
    }
    query
      .map((_: Int, r: ResultSet, _: StatementContext) => {
        TazInfo(
          r.getString("geo_id"),
          r.getString("state_fp"),
          r.getString("county_fp"),
          r.getString("tract_code"),
          r.getLong("land_area"),
          r.getLong("water_area"),
          PreparedGeometryFactory.prepare(readWkb(r.getObject("geom")))
        )
      })
      .list()
      .asScala
  }

  override def close(): Unit = connection.close()
}
