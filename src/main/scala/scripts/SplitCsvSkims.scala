package scripts

import java.nio.file.Paths

import beam.sim.BeamWarmStart
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.io.IOUtils

import scala.annotation.tailrec
import scala.reflect.io.File

object SplitCsvSkims extends App with LazyLogging {
  if (args.length != 3) {
    println("Path to input `*.csv.gz` file, output directory and number of parts should be provided.")
    System.exit(1)
  }

  def process(csvFilePath: String, outputDirectory: String, numberOfParts: Int): Unit = {
    File(outputDirectory).createDirectory()

    val reader = IOUtils.getBufferedReader(csvFilePath)
    val headers = reader.readLine()

    val filePartSuffix = BeamWarmStart.fileNameSubstringToDetectIfReadSkimsInParallelMode
    val pattern = File(csvFilePath).name.replace(".csv", filePartSuffix + "$i.csv")
    FileUtils.parWrite(Paths.get(outputDirectory), pattern, numberOfParts) { (_, _, writer) =>
      writer.write(headers)

      @tailrec
      def recur(): Unit = {
        val line = reader.readLine()
        if (line != null) {
          writer.newLine()
          writer.write(line)
          recur()
        }
      }

      recur()
    }
  }

  process(csvFilePath = args(0), outputDirectory = args(1), numberOfParts = args(2).toInt)
}
