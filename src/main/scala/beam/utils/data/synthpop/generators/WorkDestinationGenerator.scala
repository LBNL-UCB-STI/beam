package beam.utils.data.synthpop.generators

import beam.utils.data.ctpp.models.{HouseholdIncome, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import beam.utils.data.synthpop.ODSampler
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.math3.random.RandomGenerator

trait WorkDestinationGenerator {
  def next(homeLocation: String, income: Double, rndGen: RandomGenerator): Option[String]
}

class RandomWorkDestinationGenerator(val dbInfo: CTPPDatabaseInfo) extends WorkDestinationGenerator with StrictLogging {
  private val householdGeoIdToIncomeOD: Map[String, Iterable[OD[HouseholdIncome]]] =
    new HouseholdIncomeTableReader(dbInfo, ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`)
      .read()
      .groupBy(x => x.source)

  logger.info(s"householdGeoIdToIncomeOD: ${householdGeoIdToIncomeOD.size}")

  override def next(homeLocation: String, income: Double, rndGen: RandomGenerator): Option[String] = {
    householdGeoIdToIncomeOD.get(homeLocation) match {
      case Some(xs) =>
        val incomeInRange = xs.filter(od => od.attribute.contains(income.toInt))
        if (incomeInRange.isEmpty) {
          if (xs.nonEmpty) {
            // Get the nearest by distance in case if couldn't find anything
            val closest =
              xs.map(x => (x, x.attribute.distance(income.toInt))).minBy { case (_, d) => d }._1.destination
            Some(closest)
          } else {
            None
          }
        } else {
          ODSampler.sample(incomeInRange, rndGen).map(x => x.destination)
        }
      case None =>
//        logger.info(
//          s"Could not find '${homeLocation}' key as ${homeLocation} in the `householdGeoIdToIncomeOD`"
//        )
        None
    }

  }
}
