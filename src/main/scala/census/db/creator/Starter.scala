package census.db.creator
import java.nio.file.Paths

import census.db.creator.config.Hardcoded
import census.db.creator.service.fileDownloader.FileDownloadService

object Starter extends App {
//  val repo: DataRepository = new DataRepoImpl(Hardcoded.config)
//  val shape = "/Users/e.zuykin/Downloads/tl_2019_01_tract/tl_2019_01_tract.shp"
//  val features = new ShapefileRepo(shape).getFeatures()

//  repo.save(features)

  new java.io.File(Hardcoded.config.workingDir).mkdirs()
  new java.io.File(Paths.get(Hardcoded.config.workingDir, "zips").toString).mkdirs()
  new java.io.File(Paths.get(Hardcoded.config.workingDir, "shapes").toString).mkdirs()

  new FileDownloadService(Hardcoded.config).downloadZipFiles()

  println()
}
