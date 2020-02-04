package census.db.creator.database
import java.sql.ResultSet

import census.db.creator.GeometryUtil._
import census.db.creator.config.Config
import census.db.creator.domain.DataRow
import com.vividsolutions.jts.geom.Geometry
import org.skife.jdbi.v2.{DBI, StatementContext}

import scala.collection.JavaConverters._

trait DataRepository extends AutoCloseable {
  def save(data: Seq[DataRow])
  def query(border: Geometry): Seq[DataRow]
  def query(border: String): Seq[DataRow]
}

class DataRepoImpl(private val config: Config) extends DataRepository {
  private val dataTable = "data"

  private val connection = new DBI(config.db.url, config.db.user, config.db.password).open()

  override def save(data: Seq[DataRow]): Unit = {
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
        .bind("geometry", x.geometry.toText)
        .add()
    }
    batch.execute()
  }

  def query(border: Geometry): Seq[DataRow] =
    connection
      .createQuery(s"""
        select *, st_asbinary(geometry) as geom from $dataTable
        where st_intersects(geometry, st_geomfromtext(:geometry, $projection))
      """.stripMargin)
      .bind("geometry", border.toText)
      .map((_: Int, r: ResultSet, _: StatementContext) => {
        DataRow(
          r.getString("geo_id"),
          r.getString("state_fp"),
          r.getString("county_fp"),
          r.getString("tract_code"),
          r.getLong("land_area"),
          r.getLong("water_area"),
          readWkb(r.getObject("geom"))
        )
      })
      .list()
      .asScala

  override def query(border: String): Seq[DataRow] = this.query(readWkt(border))

  override def close(): Unit = connection.close()
}
