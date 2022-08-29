package beam.utils.data.synthpop

import beam.utils.data.ctpp.models.{AgeRange, OD, ResidenceGeography, ResidenceToWorkplaceFlowGeography}
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.AgeOfWorkerTableReader
import beam.utils.data.ctpp.readers.residence.AgeTableReader
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.math3.random.{MersenneTwister, RandomGenerator}

private case class PeopleAndWorkers(totalPeople: Int, totalWorkers: Int)

class WorkForceSampler(val dbInfo: CTPPDatabaseInfo, val stateCode: String, val randomGenerator: RandomGenerator)
    extends StrictLogging {

  private val workersAgesOD: Iterable[OD[AgeRange]] =
    new AgeOfWorkerTableReader(dbInfo, ResidenceToWorkplaceFlowGeography.`State To State`)
      .read()
      .filter(od => od.source == stateCode && od.destination == stateCode)

  private val populationAge = new AgeTableReader(dbInfo, ResidenceGeography.State)
    .read()
  require(populationAge.contains(stateCode), s"Can't find state $stateCode in ${populationAge.keySet}")
  private val stateAges = populationAge(stateCode).filter { case (ageRng, _) => ageRng.range.start >= 16 }

  private val workerAgeToStats = workersAgesOD
    .foldLeft(List.empty[(AgeRange, PeopleAndWorkers)]) { case (acc, od) =>
      val workerAgeRng = od.attribute.range
      // Inefficient way to find subranges in the range
      // But the number of elements is less than 10, so we should be fine
      val matchedStateAges = stateAges.flatMap { case (stateRng, cnt) =>
        val intersection = stateRng.range.intersect(workerAgeRng)
        if (intersection.nonEmpty) {
          Some(Range.inclusive(intersection.head, intersection.last), cnt)
        } else
          None
      }
      val totalPeople = matchedStateAges.values.sum.toInt
      (AgeRange(workerAgeRng) -> PeopleAndWorkers(totalPeople, od.value.toInt)) :: acc
    }
    .sortBy(x => x._1.range.start)

  workerAgeToStats.foreach { case (ageRng, stat) =>
    val ratio = stat.totalWorkers.toDouble / stat.totalPeople
    logger.info(
      s"$ageRng => Total people: ${stat.totalPeople}, total workers: ${stat.totalWorkers}, ratio: ${ratio.formatted("%.3f")}"
    )
  }

  private val workerAgeToEmploymentRatio: List[(AgeRange, Double)] = workerAgeToStats.map { case (ageRng, stat) =>
    (ageRng, stat.totalWorkers.toDouble / stat.totalPeople)
  }

  def isWorker(age: Int): Boolean = {
    workerAgeToEmploymentRatio.find { case (ageRng, _) => ageRng.range.contains(age) } match {
      case Some((_, ratio)) =>
        val probability = ratio
        randomGenerator.nextDouble() < probability
      case None =>
        logger.warn(s"Can't find person with age $age in ${workerAgeToEmploymentRatio.mkString(" ")}")
        false
    }
  }
}

object WorkForceSampler {

  def main(args: Array[String]): Unit = {
    val databaseInfo = CTPPDatabaseInfo(PathToData("d:/Work/beam/Austin/input/CTPP/"), Set("48"))
    val wfs = new WorkForceSampler(databaseInfo, "48", new MersenneTwister(42))

    verfiyProbability(wfs, 16, 0.148)
    verfiyProbability(wfs, 17, 0.148)
    verfiyProbability(wfs, 25, 0.741)
    verfiyProbability(wfs, 44, 0.741)
    verfiyProbability(wfs, 45, 0.711)
    verfiyProbability(wfs, 59, 0.711)
    verfiyProbability(wfs, 60, 0.518)
    verfiyProbability(wfs, 64, 0.518)
    verfiyProbability(wfs, 65, 0.255)
    verfiyProbability(wfs, 74, 0.255)
    verfiyProbability(wfs, 75, 0.063)
  }

  private def verfiyProbability(wfs: WorkForceSampler, age: Int, expectedProbability: Double): Unit = {
    val isWorkerList = (1 to 100000).map { _ =>
      wfs.isWorker(age)
    }
    val numOfWorkers = isWorkerList.count(_ == true)
    val ratio = numOfWorkers.toDouble / isWorkerList.size

    println(s"numOfWorkers: $numOfWorkers, isWorkerList size: ${isWorkerList.size}, ratio: $ratio")
    val absDiff = Math.abs(expectedProbability - ratio)
    require(absDiff < 1e-2, "Something wrong with probability function?")
  }
}
