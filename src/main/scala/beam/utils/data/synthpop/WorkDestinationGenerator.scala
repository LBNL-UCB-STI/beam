package beam.utils.data.synthpop

import beam.utils.data.ctpp.models.{HouseholdIncome, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.PathToData
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import beam.utils.data.synthpop.models.Models.{PowPumaGeoId, PumaGeoId}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.math3.random.MersenneTwister

trait WorkDestinationGenerator {
  def next(homeLocation: PumaGeoId, income: Double): Option[PowPumaGeoId]
}

class RandomWorkDestinationGenerator(val pathToCTPPData: PathToData, val randomSeed: Int)
    extends WorkDestinationGenerator
    with StrictLogging {
  private val rndGen: MersenneTwister = new MersenneTwister(randomSeed) // Random.org
  private val householdGeoIdToIncomeOD: Map[String, Seq[OD[HouseholdIncome]]] =
    new HouseholdIncomeTableReader(pathToCTPPData, ResidenceToWorkplaceFlowGeography.`PUMA5 To POWPUMA`)
      .read()
      .groupBy(x => x.source)

  logger.info(s"householdGeoIdToIncomeOD: ${householdGeoIdToIncomeOD.size}")

  override def next(homeLocation: PumaGeoId, income: Double): Option[PowPumaGeoId] = {
    householdGeoIdToIncomeOD.get(homeLocation.asUniqueKey) match {
      case Some(xs) =>
        val incomeInRange = xs.filter(od => od.attribute.range.contains(income.toInt))
        if (incomeInRange.isEmpty) {
          logger.info(s"Could not find OD with income ${income} in ${xs.mkString(" ")}")
        }
        ODSampler.sample(incomeInRange, rndGen).map(x => PowPumaGeoId.fromString(x.destination))
      case None =>
        logger.info(
          s"Could not find '${homeLocation}' key as ${homeLocation.asUniqueKey} in the `householdGeoIdToIncomeOD`"
        )
        None
    }

  }
}
