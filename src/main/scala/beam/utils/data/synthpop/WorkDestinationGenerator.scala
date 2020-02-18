package beam.utils.data.synthpop

import beam.utils.data.ctpp.models.{HouseholdIncome, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.PathToData
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import beam.utils.data.synthpop.models.Models.TazGeoId
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}

import scala.collection.JavaConverters._

trait WorkDestinationGenerator {
  def next(homeLocation: TazGeoId, income: Double): Option[TazGeoId]
}

class RandomWorkDestinationGenerator(val pathToCTPPData: PathToData, val randomSeed: Int)
    extends WorkDestinationGenerator
    with StrictLogging {
  private val rndGen: MersenneTwister = new MersenneTwister(randomSeed) // Random.org
  private val householdGeoIdToIncomeOD: Map[TazGeoId, Seq[OD[HouseholdIncome]]] =
    new HouseholdIncomeTableReader(pathToCTPPData, ResidenceToWorkplaceFlowGeography.`PUMA5 To POWPUMA`)
      .read()
      .groupBy(x => x.source)
      .map {
        case (geoId, xs) =>
          val tazGeoId = TazGeoId(geoId)
          tazGeoId -> xs
      }

  logger.info(s"householdGeoIdToIncomeOD: ${householdGeoIdToIncomeOD.size}")

  override def next(homeLocation: TazGeoId, income: Double): Option[TazGeoId] = {
    householdGeoIdToIncomeOD.get(homeLocation).map { xs =>
      val findDestinationByIncome = xs.filter(od => od.attribute.range.contains(income.toInt))
      val probabilityMassFunction = findDestinationByIncome.map { od =>
        val destTazGeoId = TazGeoId(od.destination)
        new CPair[TazGeoId, java.lang.Double](destTazGeoId, od.value)
      }
      val distr = new EnumeratedDistribution[TazGeoId](rndGen, probabilityMassFunction.asJava)
      val sampledTazGeoId = distr.sample()
      sampledTazGeoId
    }
  }
}
