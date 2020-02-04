package census.db.creator
import census.db.creator.config.Hardcoded
import census.db.creator.database.{DataRepoImpl, DataRepository}
import census.db.creator.shape.ShapefileRepo

object Starter extends App {
  val repo: DataRepository = new DataRepoImpl(Hardcoded.config)

//  val a = repo.query("polygon ((0 0, 0 2, 2 2, 2 0, 0 0))")

  val shape = "/Users/e.zuykin/Downloads/tl_2019_01_tract/tl_2019_01_tract.shp"
  val features = new ShapefileRepo(shape).getFeatures()

  repo.save(features)

  println()
}
