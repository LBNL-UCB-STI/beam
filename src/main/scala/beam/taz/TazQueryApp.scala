package beam.taz
import census.db.creator.database.{PostgresTazRepo, TazRepository}

object TazQueryApp extends App {
  require(args.length == 1, "PBF path should be specified")

  val pbf = args(0)

  val osmService = new OsmService(pbf)

  val tazRepo: TazRepository = new PostgresTazRepo()

  val tazesInsideOfBoundingBox = tazRepo.query(border = Some(osmService.boundingBox()))

  val tazCoordinateGenerator = new TazCoordinateGeneratorImpl(osmService, tazRepo)

  val before = System.currentTimeMillis()

  val coordinates =
    tazesInsideOfBoundingBox.map(c => tazCoordinateGenerator.generate(c.geoId, 2000))

  println(System.currentTimeMillis() - before)
  println()
}
