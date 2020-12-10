package beam.utils.shapefiles

import java.nio.file.{Files, Paths}

import beam.agentsim.infrastructure.geozone.aggregation.H3IndexFileReader
import beam.agentsim.infrastructure.geozone.GeoZoneUtil

object WriteShapeFile extends App {
  if (args.length != 2) {
    val msg =
      """You should specify the outputFile as parameter:
         |sbt "runMain beam.utils.shapefiles.WriteShapeFile <inputFileGeoIndexFile> <outputFile>"
         |""".stripMargin
    println(msg)
  } else {
    val indexesFile = Paths.get(args(0))
    val outputPath = Paths.get(args(1))
    if (Files.isRegularFile(indexesFile)) {
      val indexes = H3IndexFileReader.readIndexes(indexesFile)
      GeoZoneUtil.writeToShapeFile(outputPath, indexes)
    } else {
      System.err.println(s"The file [$indexesFile] does not exist.")
    }
  }
}
