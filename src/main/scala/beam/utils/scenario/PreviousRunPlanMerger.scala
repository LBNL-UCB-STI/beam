package beam.utils.scenario

import beam.utils.scenario.generic.readers.CsvPlanElementReader

import java.nio.file.{Files, Path}
import java.util.stream.Collectors
import scala.collection.mutable
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class PreviousRunPlanMerger(fraction: Double, outputDir: Path, dirPrefix: String) {

  def merge(plans: Iterable[PlanElement]): Iterable[PlanElement] = {
    if (fraction > 0) {
      LastRunOutputSource.findLastRunOutputPlans(outputDir, dirPrefix) match {
        case Some(planPath) =>
          val previousPlans = CsvPlanElementReader.read(planPath.toString)
          merge(previousPlans, plans, fraction, new Random())
        case None =>
          plans
      }
    } else {
      plans
    }
  }

  def merge(
    plans: Iterable[PlanElement],
    plansToMerge: Iterable[PlanElement],
    fraction: Double,
    random: Random
  ): Iterable[PlanElement] = {
    val plansByPerson = plans.groupBy { _.personId }
    val plansToMergeByPerson = plansToMerge.groupBy { _.personId }

    val plansByPersonFiltered = plansByPerson.filterKeys { plansToMergeByPerson.contains }

    val replacePersonPlansCount = (plansByPersonFiltered.size * fraction).round.toInt

    val availablePersonsToUpdate = mutable.Buffer() ++= plansByPersonFiltered.keys

    val personsToChange = for {_ <- 0 until replacePersonPlansCount if availablePersonsToUpdate.nonEmpty} yield
      availablePersonsToUpdate.remove(random.nextInt(availablePersonsToUpdate.size))

    (plansByPerson ++ plansToMergeByPerson.filterKeys { personsToChange.contains(_) })
      .flatten { case (_, plans) => plans }
  }
}

object LastRunOutputSource {

  def findLastRunOutputPlans(outputPath: Path, dirPrefix: String): Option[Path] = {
    val IterationNumber = """it.(\d+)""".r
    val dirs = findDirs(outputPath, dirPrefix)
      .sortWith((path1, path2) => path1.getFileName.toString.compareTo(path2.getFileName.toString) > 0)
    for {
      lastOutputDir <- dirs.find(path => Files.exists(path.resolve("ITERS")))
      plansPath <- findDirs(lastOutputDir.resolve("ITERS"), "it.")
        .map { itPath =>
          val itNumber = itPath.getFileName.toString match {
            case IterationNumber(num) => num.toInt
            case _                    => Int.MinValue
          }
          itPath -> itNumber
        }
        .filter { case (_, itNumber) => itNumber >= 0 }
        .sortBy { case (_, itNumber) => -itNumber }
        .collectFirst {
          case (itPath, itNumber) if Files.exists(itPath.resolve(s"$itNumber.plans.csv.gz")) =>
            itPath.resolve(s"$itNumber.plans.csv.gz")
        }
    } yield plansPath
  }

  import collection.JavaConverters._
  private def findDirs(parentDir: Path, prefix: String) =
    Files
      .find(parentDir, 1, (path: Path, attr) => attr.isDirectory && path.getFileName.toString.startsWith(prefix))
      .collect(Collectors.toList[Path])
      .asScala
}
