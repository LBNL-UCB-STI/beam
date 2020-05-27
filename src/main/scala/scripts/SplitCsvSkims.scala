package scripts

import com.typesafe.scalalogging.LazyLogging
import org.matsim.core.utils.io.IOUtils

object SplitCsvSkims extends App with LazyLogging {
  if (args.length != 2) {
    println("Path to `*.csv.gz` file and number of parts should be provided.")
    System.exit(1)
  }

  val csvFilePath = args(0)
  val partitionCount = args(1).toInt

  val bufferWritersMap = (1 to partitionCount).map(i =>
    i.toLong -> IOUtils.getBufferedWriter(csvFilePath.replace(".gz", s"_part$i.gz"))
  ).toMap

  val reader = IOUtils.getBufferedReader(csvFilePath)
  val headers = reader.readLine()

  bufferWritersMap.foreach { case (_, bw) =>
    bw.write(headers)
  }

  var counter = 0L
  reader.lines().forEach(line => {
    val part = (counter % partitionCount) + 1
    bufferWritersMap.get(part) match {
      case Some(bw) =>
        bw.newLine()
        bw.write(line)
      case None => throw new IllegalStateException(s"Buffer writer for part $part was not found")
    }
    counter += 1
  })

  bufferWritersMap.foreach { case (_, bw) =>
    bw.flush()
    bw.close()
  }
}
