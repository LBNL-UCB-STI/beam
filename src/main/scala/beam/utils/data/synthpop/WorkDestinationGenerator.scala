package beam.utils.data.synthpop

import beam.utils.data.ctpp.models.{HouseholdIncome, OD, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.PathToData
import beam.utils.data.ctpp.readers.flow.HouseholdIncomeTableReader
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => CPair}

import scala.collection.JavaConverters._

trait WorkDestinationGenerator {
  def next(homeLocation: String, income: Double): Option[String]
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

  override def next(homeLocation: String, income: Double): Option[String] = {
    householdGeoIdToIncomeOD.get(homeLocation).map { xs =>
      val findDestinationByIncome = xs.filter(od => od.attribute.range.contains(income.toInt))
      val probabilityMassFunction = findDestinationByIncome.map { od =>
        new CPair[String, java.lang.Double](od.destination, od.value)
      }
      val distr = new EnumeratedDistribution[String](rndGen, probabilityMassFunction.asJava)
      val sampledTazGeoId = distr.sample()
      sampledTazGeoId
    }
  }
}
