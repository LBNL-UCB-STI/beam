package parsers

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.router.skim.{AbstractSkimmerInternal, AbstractSkimmerKey}
import beam.utils.FileUtils
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, mutable}

// Current Skimmer implementation
object SupercsvParser extends App with LazyLogging {
  val start = System.currentTimeMillis()

  val res = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
  var mapReader: CsvMapReader = new CsvMapReader(FileUtils.readerFromFile("/Users/crixal/work/2644/0.skims_for_Dmitry_sample.csv.gz"), CsvPreference.STANDARD_PREFERENCE)

  var counter: Long = 0
  try {
    val header = mapReader.getHeader(true)
    var line: java.util.Map[String, String] = mapReader.read(header: _*)
    while (null != line) {
      import scala.collection.JavaConverters._
      val newPair = fromCsv(line.asScala.toMap)
      res.put(newPair._1, newPair._2)
      line = mapReader.read(header: _*)

      //      counter += 1
      //      if (counter % 5000000 == 0) {
      //        logger.info("Processed: {} rows", counter)
      //      }
    }
  } finally {
    if (null != mapReader)
      mapReader.close()
  }

  val resMap = res.toMap
  val end = System.currentTimeMillis()

  logger.info("Loaded to map rows: {}", resMap.size)
  logger.info("Total time: {}", end - start)


  def fromCsv(row: immutable.Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODSkimmerKey(
        hour = row("hour").toInt,
        mode = BeamMode.fromString(row("mode").toLowerCase()).get,
        originTaz = Id.create(row("origTaz"), classOf[TAZ]),
        destinationTaz = Id.create(row("destTaz"), classOf[TAZ])
      ),
      ODSkimmerInternal(
        travelTimeInS = row("travelTimeInS").toDouble,
        generalizedTimeInS = row("generalizedTimeInS").toDouble,
        generalizedCost = row("generalizedCost").toDouble,
        distanceInM = row("distanceInM").toDouble,
        cost = row("cost").toDouble,
        energy = Option(row("energy")).map(_.toDouble).getOrElse(0.0),
        observations = row("observations").toInt,
        iterations = row("iterations").toInt
      )
    )
  }
}
