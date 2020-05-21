package parsers

import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.router.skim.{AbstractSkimmerInternal, AbstractSkimmerKey}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils
import org.simpleflatmapper.lightningcsv.CsvParser
import scala.collection.JavaConverters._

// It's about 20% faster than Univocity, but it's a new one dependency. Should be investigated before use.
// Benchmark https://simpleflatmapper.org/12-csv-performance.html
// Should be compared with ConcurrentUnivocity
object SimpleflatmapperParser extends App with LazyLogging {

  val start = System.currentTimeMillis()

  var mapReader = CsvParser
    .skip(1)
    .parallelReader()
    .iterator(IOUtils.getBufferedReader("/Users/crixal/work/2644/0.skims_for_Dmitry_sample.csv.gz"))
    .asScala

  var counter: Long = 0
  val res = mapReader.map(row => {
    val newPair = fromCsv(row)

    //    counter += 1
    //    if (counter % 5000000 == 0) {
    //      logger.info("Processed: {} rows", counter)
    //    }
    newPair
  }).toMap

  val end = System.currentTimeMillis()

  logger.info("Loaded to map rows: {}", res.size)
  logger.info("Total time: {}", end - start)


  def fromCsv(row: Array[String]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODSkimmerKey(
        hour = row(0).toInt,
        mode = BeamMode.fromString(row(1).toLowerCase()).get,
        originTaz = Id.create(row(2), classOf[TAZ]),
        destinationTaz = Id.create(row(3), classOf[TAZ])
      ),
      ODSkimmerInternal(
        travelTimeInS = row(4).toDouble,
        generalizedTimeInS = row(5).toDouble,
        generalizedCost = row(7).toDouble,
        distanceInM = row(8).toDouble,
        cost = row(6).toDouble,
        energy = Option(row(9)).map(_.toDouble).getOrElse(0.0),
        observations = row(10).toInt,
        iterations = row(11).toInt
      )
    )
  }
}
