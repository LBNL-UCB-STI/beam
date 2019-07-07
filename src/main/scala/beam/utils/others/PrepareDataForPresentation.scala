package beam.utils.others

import java.io.{Closeable, Writer}

import beam.agentsim.events.PathTraversalEvent
import beam.router.Modes
import beam.utils.{EventReader, ProfilingUtils}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.core.utils.io.IOUtils

import scala.util.Try

case class Delimiter(value: String = ",")
case class LineSeparator(value: String = System.lineSeparator())

class CsvWriter(
  path: String,
  headers: IndexedSeq[String],
  implicit val delimiter: Delimiter = Delimiter(),
  implicit val lineSeparator: LineSeparator = LineSeparator()
) extends AutoCloseable {
  implicit val writer: Writer = IOUtils.getBufferedWriter(path)
  CsvWriter.writeHeader(headers)

  def writeColumn(value: Any, shouldAddDelimiter: Boolean = true): Unit = {
    CsvWriter.writeColumnValue(value, shouldAddDelimiter)
  }

  def write(xs: Any*): Unit = {
    writeRow(xs.toVector)
  }

  def writeRow(values: IndexedSeq[Any]): Unit = {
    values.zipWithIndex.foreach {
      case (value, idx) =>
        val shouldAddDelimiter = idx != values.length - 1
        CsvWriter.writeColumnValue(value, shouldAddDelimiter)
    }
    CsvWriter.writeLineSeparator
  }

  def writeNewLine(): Unit = {
    CsvWriter.writeLineSeparator
  }

  override def close(): Unit = {
    Try(writer.close())
  }
}

object CsvWriter {

  def writeColumnValue(
    value: Any,
    shouldAddDelimiter: Boolean = true
  )(implicit wrt: Writer, delimiter: Delimiter): Unit = {
    wrt.append(value.toString)
    if (shouldAddDelimiter)
      wrt.append(delimiter.value)
  }

  def writeHeader(
    headers: IndexedSeq[String]
  )(implicit wrt: Writer, delimiter: Delimiter, lineSeparator: LineSeparator): Unit = {
    headers.zipWithIndex.foreach {
      case (header, idx) =>
        val shouldAddDelimiter = idx != headers.size - 1
        writeColumnValue(header, shouldAddDelimiter)
    }
    wrt.append(lineSeparator.value)
    wrt.flush()
  }

  def writeLineSeparator(implicit wrt: Writer, lineSeparator: LineSeparator): Unit = {
    wrt.write(lineSeparator.value)
  }
}

object PrepareDataForPresentation extends LazyLogging {

  def filter(event: Event): Boolean = {
    val attribs = event.getAttributes
    // We need only PathTraversal with mode != `walk`
    val isNeededEvent = event.getEventType == "PathTraversal" && !Option(attribs.get("mode")).contains("walk")
    isNeededEvent
  }

  def main(args: Array[String]): Unit = {
    require(args.length == 2, "Must provide two args: 1-st arg is path to events file, 2-nd path to output folder")
    val pathToEvents = args(0) // "D:/Work/beam/NewSeriesOfRuns/High Intercept & high radius/Full/urbansim-25k__2019-06-21_16-41-50_NoReposition/ITERS/it.0/0.events.csv.gz" //
    val outputFolderPath = args(1)
    logger.info(s"Path to events: $pathToEvents")
    logger.info(s"Path to output folder: $outputFolderPath")

    // This is lazy, it just creates an iterator
    val (events: Iterator[Event], closable: Closeable) = EventReader.fromCsvFile(pathToEvents, filter)
    try {
      // Actual reading happens here because we force computation by `toArray`
      val pathTraversalEvents = ProfilingUtils.timed("Read PathTraversal and filter by mode", x => logger.info(x)) {
        events.map(PathTraversalEvent.apply).toArray
      }
      val maxTime = pathTraversalEvents.maxBy(x => x.arrivalTime).arrivalTime
      logger.info(s"pathTraversalEvents size: ${pathTraversalEvents.length}, maxTime: $maxTime")
      // Granularity in seconds
      val granularity = 5

      createVehiclesPerHourWithModes(
        granularity,
        maxTime,
        pathTraversalEvents,
        s"$outputFolderPath/time_to_num_of_vehicles_per_mode_$granularity.csvh"
      )

      createRideHailVsNormalCar(
        granularity,
        maxTime,
        pathTraversalEvents,
        s"$outputFolderPath/ride-hail_vs_normal-car_$granularity.csvh"
      )
      //      val vehiclesPerHourPath = s"$outputFolderPath/time_to_num_of_vehicles_$granularity.csvh"
      //      createVehiclesPerHour(granularity, maxTime, pathTraversalEvents, vehiclesPerHourPath)
      //
      //      val rideHailVehiclesPerHourPath = s"$outputFolderPath/time_to_num_of_ride-hail_vehicles_$granularity.csvh"
      //      createRideHailVehiclesPerHour(granularity, maxTime, pathTraversalEvents, rideHailVehiclesPerHourPath)
    } finally {
      closable.close()
    }
  }

  def getVehiclesPerHour(pathTraversalEvents: Array[PathTraversalEvent], granularity: Int, maxTime: Int): Array[Int] = {
    val numOfBins = maxTime / granularity
    logger.info(s"Granularity: $granularity, maxArrivalTime: $maxTime, numOfBins: $numOfBins")
    val timeToNumberOfVehicles = Array.fill[Int](numOfBins + 1)(0)

    ProfilingUtils.timed("Filling up timeToNumberOfVehicles", x => logger.info(x)) {
      pathTraversalEvents.foreach { pte =>
        Range(pte.departureTime, pte.arrivalTime, granularity).foreach { t =>
          val binIdx = t / granularity
          val counter = timeToNumberOfVehicles(binIdx)
          timeToNumberOfVehicles.update(binIdx, counter + 1)
        }
      }
    }
    timeToNumberOfVehicles
  }

  def getVehiclesPerHourWithModes(
    pathTraversalEvents: Array[PathTraversalEvent],
    granularity: Int,
    maxTime: Int
  ): (Array[Modes.BeamMode], Array[Array[Int]]) = {
    val numOfBins = maxTime / granularity
    val sortedModes = pathTraversalEvents.map(_.mode).distinct.sortBy(x => x.value)
    logger.info(
      s"Granularity: $granularity, maxArrivalTime: $maxTime, numOfBins: $numOfBins, all modes: ${sortedModes.mkString(" ")}"
    )
    val timeToNumberOfVehiclesPerMode = Array.fill[Int](numOfBins + 1, sortedModes.length)(0)

    ProfilingUtils.timed("Filling up timeToNumberOfVehiclesPerMode", x => logger.info(x)) {
      pathTraversalEvents.foreach { pte =>
        Range(pte.departureTime, pte.arrivalTime, granularity).foreach { t =>
          val binIdx = t / granularity
          val perModeBucket = timeToNumberOfVehiclesPerMode(binIdx)
          val offsetForMode = sortedModes.indexOf(pte.mode)
          val counter = perModeBucket(offsetForMode)
          perModeBucket.update(offsetForMode, counter + 1)
        }
      }
    }
    (sortedModes, timeToNumberOfVehiclesPerMode)
  }

  def createVehiclesPerHour(
    granularity: Int,
    maxTime: Int,
    pathTraversalEvents: Array[PathTraversalEvent],
    writePath: String
  ): Unit = {
    val timeToNumberOfVehicles: Array[Int] = getVehiclesPerHour(pathTraversalEvents, granularity, maxTime)
    val csvWriter = new CsvWriter(writePath, Vector("time", "counter"))
    // TODO If it is bottleneck, replace by `while` loop
    timeToNumberOfVehicles.zipWithIndex.foreach {
      case (counter, binIdx) =>
        val time = binIdx * granularity
        csvWriter.write(time, counter)
    }
    csvWriter.close()
  }

  def createRideHailVehiclesPerHour(
    granularity: Int,
    maxTime: Int,
    pathTraversalEvents: Array[PathTraversalEvent],
    writePath: String
  ): Unit = {
    val onlyRideHailVehicles = pathTraversalEvents.filter(x => x.vehicleId.toString.contains("rideHailVehicle-"))
    val timeToNumberOfVehicles: Array[Int] = getVehiclesPerHour(onlyRideHailVehicles, granularity, maxTime)
    val csvWriter = new CsvWriter(writePath, Vector("time", "counter"))
    // TODO If it is bottleneck, replace by `while` loop
    timeToNumberOfVehicles.zipWithIndex.foreach {
      case (counter, binIdx) =>
        val time = binIdx * granularity
        csvWriter.write(time, counter)
    }
    csvWriter.close()
  }

  def createVehiclesPerHourWithModes(
    granularity: Int,
    maxTime: Int,
    pathTraversalEvents: Array[PathTraversalEvent],
    writePath: String
  ): Unit = {
    val (allModes, vehPerHourWithMode) = getVehiclesPerHourWithModes(pathTraversalEvents, granularity, maxTime)
    val csvWriter = new CsvWriter(writePath, Vector("time", "category", "counter"))
    vehPerHourWithMode.zipWithIndex.foreach {
      case (counterPerMode, binIdx) =>
        val time = binIdx * granularity
        counterPerMode.zipWithIndex.foreach {
          case (counter, modeIdx) =>
            val modeName = allModes(modeIdx)
            csvWriter.write(time, modeName, counter)
        }
        csvWriter.write(time, "total", counterPerMode.sum)
    }
    csvWriter.close()
  }

  def createRideHailVsNormalCar(
    granularity: Int,
    maxTime: Int,
    pathTraversalEvents: Array[PathTraversalEvent],
    writePath: String
  ): Unit = {
    val numOfBins = maxTime / granularity
    logger.info(s"Granularity: $granularity, maxArrivalTime: $maxTime, numOfBins: $numOfBins")
    val categories = Array("ride-hail", "car")
    val timeToNumberOfVehiclesPerCategory = Array.fill[Int](numOfBins + 1, categories.length)(0)
    ProfilingUtils.timed("Filling up timeToNumberOfVehiclesPerCategory", x => logger.info(x)) {
      pathTraversalEvents.foreach { pte =>
        val category = if (pte.vehicleId.toString.contains("rideHailVehicle-")) "ride-hail" else "car"
        Range(pte.departureTime, pte.arrivalTime, granularity).foreach { t =>
          val binIdx = t / granularity
          val perCategory = timeToNumberOfVehiclesPerCategory(binIdx)
          val offset = categories.indexOf(category)
          val counter = perCategory(offset)
          perCategory.update(offset, counter + 1)
        }
      }
    }
    val csvWriter = new CsvWriter(writePath, Vector("time", "category", "counter"))
    timeToNumberOfVehiclesPerCategory.zipWithIndex.foreach {
      case (counterPerCategory, binIdx) =>
        val time = binIdx * granularity
        counterPerCategory.zipWithIndex.foreach {
          case (counter, modeIdx) =>
            val categoryName = categories(modeIdx)
            csvWriter.write(time, categoryName, counter)
        }
    }
    csvWriter.close()
  }
}
