package parsers

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.router.skim.{AbstractSkimmerInternal, AbstractSkimmerKey}
import com.typesafe.scalalogging.LazyLogging
import com.univocity.parsers.common.record.Record
import com.univocity.parsers.csv.{CsvParser, CsvParserSettings}
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils
import scala.collection.JavaConverters._

// It's about 30% faster than Supercsv
// Already used in the project for example VehicleEnergy.scala
object UnivocityParser extends App with LazyLogging {
  val start = System.currentTimeMillis()

  val settings = new CsvParserSettings()
  settings.setHeaderExtractionEnabled(true)
  settings.detectFormatAutomatically()

  val csvParser = new CsvParser(settings)
  var mapReader = csvParser.iterateRecords(IOUtils.getBufferedReader("/Users/crixal/work/2644/0.skims_for_Dmitry_sample.csv.gz")).asScala

  var counter: Long = 0

  val res = mapReader.map(rec => {
    val newPair = fromCsv(rec)

    //    counter += 1
    //    if (counter % 5000000 == 0) {
    //      logger.info("Processed: {} rows", counter)
    //    }
    newPair
  }).toMap

  val end = System.currentTimeMillis()

  logger.info("Loaded to map rows: {}", res.size)
  logger.info("Total time: {}", end - start)


  def fromCsv(rec: Record): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODSkimmerKey(
        hour = rec.getInt("hour"),
        mode = BeamMode.fromString(rec.getString("mode").toLowerCase()).get,
        originTaz = Id.create(rec.getString("origTaz"), classOf[TAZ]),
        destinationTaz = Id.create(rec.getString("destTaz"), classOf[TAZ])
      ),
      ODSkimmerInternal(
        travelTimeInS = rec.getDouble("travelTimeInS"),
        generalizedTimeInS = rec.getDouble("generalizedTimeInS"),
        generalizedCost = rec.getDouble("generalizedCost"),
        distanceInM = rec.getDouble("distanceInM"),
        cost = rec.getDouble("cost"),
        energy = Option(rec.getDouble("energy")).map(_.toDouble).getOrElse(0.0),
        observations = rec.getInt("observations"),
        iterations = rec.getInt("iterations")
      )
    )
  }
}
