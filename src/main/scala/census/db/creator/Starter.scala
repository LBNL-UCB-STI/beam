package census.db.creator
import census.db.creator.config.Hardcoded
import census.db.creator.database.{DataRepoImpl, DataRepository}
import census.db.creator.domain.DataRow

object Starter extends App {
  val repo: DataRepository = new DataRepoImpl(Hardcoded.config)

  repo.save(Seq(DataRow("1", "2", "3", "4", 5, 6, GeometryUtil.readWkt("point(1 1)"))))

  val a = repo.query("polygon ((0 0, 0 2, 2 2, 2 0, 0 0))")
  println()
}
