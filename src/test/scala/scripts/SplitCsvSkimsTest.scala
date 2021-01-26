package scripts

import java.nio.file.Files

import beam.router.skim.{CsvSkimReader, ODSkimmer}
import beam.sim.BeamWarmStart
import com.typesafe.scalalogging.StrictLogging
import org.scalatest.FunSuite

import scala.reflect.io.File

class SplitCsvSkimsTest extends FunSuite with StrictLogging {

  test("Split skims file to parts") {
    val basePath = System.getenv("PWD")
    val inputFilePath = s"$basePath/test/test-resources/beam/od-skims/od_for_test.csv.gz"
    val outputDirectory = Files.createTempDirectory("beam-tests").toString
    val numberOfParts = 3

    SplitCsvSkims.process(inputFilePath, outputDirectory, numberOfParts)

    val paths = File(outputDirectory).toDirectory.files
      .filter(_.isFile)
      .filter(_.name.contains(BeamWarmStart.fileNameSubstringToDetectIfReadSkimsInParallelMode))
      .map(_.path)
      .toList

    assert(paths.size == numberOfParts)

    val origData = new CsvSkimReader(inputFilePath, ODSkimmer.fromCsv, logger).readAggregatedSkims

    val splitData = paths
      .flatMap(path => {
        new CsvSkimReader(path, ODSkimmer.fromCsv, logger).readAggregatedSkims
      })
      .toMap

    assert(origData == splitData)
  }
}
