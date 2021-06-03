package beam.utils.scenario

import beam.utils.scenario.generic.readers.CsvPlanElementReader
import com.typesafe.scalalogging.LazyLogging

import java.nio.file.{Files, Path}
import java.util.stream.Collectors
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class PreviousRunPlanMerger(
  fraction: Double,
  outputDir: Path,
  dirPrefix: String,
  rnd: Random,
  adjustForScenario: PlanElement => PlanElement
) extends LazyLogging {
  assert(0 <= fraction && fraction <= 1.0, "fraction is wrong")

  def merge(plans: Iterable[PlanElement]): Iterable[PlanElement] = {
    if (fraction > 0) {
      LastRunOutputSource.findLastRunOutputPlans(outputDir, dirPrefix) match {
        case Some(planPath) =>
          logger.info("Found the plans in the beam output directory: {}", planPath)
          val previousPlans = CsvPlanElementReader.read(planPath.toString)
          val convertedPlans = previousPlans.map(adjustForScenario)
          PreviousRunPlanMerger.merge(convertedPlans, plans, fraction, rnd)
        case None =>
          logger.warn(
            "Not found appropriate output plans in the beam output directory: {}, dirPrefix = {}",
            outputDir,
            dirPrefix
          )
          plans
      }
    } else {
      plans
    }
  }
}

object PreviousRunPlanMerger extends LazyLogging {

  def merge(
    plans: Iterable[PlanElement],
    plansToMerge: Iterable[PlanElement],
    fraction: Double,
    random: Random
  ): Iterable[PlanElement] = {
    val persons = plans.map(_.personId).toSet
    val mergePersons = plansToMerge.map(_.personId).toSet
    val matchedPersons = persons & mergePersons
    val numberToReplace = (persons.size * fraction).round.toInt
    val personIdsToReplace = random.shuffle(matchedPersons.toSeq).take(numberToReplace).toSet
    logger.info("Replacing {} people plans", personIdsToReplace.size)
    val shouldReplace = (plan: PlanElement) => personIdsToReplace.contains(plan.personId)
    plans.filterNot(shouldReplace) ++ plansToMerge.filter(shouldReplace)
  }
}

object LastRunOutputSource {

  def findLastRunOutputPlans(outputPath: Path, dirPrefix: String): Option[Path] = {
    def findPlan(iterationDir: Path, iterationNumber: Int, format: String): Option[Path] = {
      val filePath = iterationDir.resolve(s"$iterationNumber.plans.$format.gz")
      println(s"filePath = ${filePath}")
      Some(filePath).filter(Files.exists(_))
    }

    val IterationNumber = """it.(\d+)""".r

    val plansPaths = for {
      outputDir <- findDirs(outputPath, dirPrefix)
        .filter(path => Files.exists(path.resolve("ITERS")))
        .sortWith((path1, path2) => path1.getFileName.toString.compareTo(path2.getFileName.toString) > 0)
        .view
      (iterationDir, iterationNumber) <- findDirs(outputDir.resolve("ITERS"), "it.")
        .flatMap(
          itPath =>
            itPath.getFileName.toString match {
              case IterationNumber(num) => Some(itPath -> num.toInt)
              case _                    => None
          }
        )
        .sortBy { case (_, itNumber) => -itNumber }
        .view
      plansPath <- findPlan(iterationDir, iterationNumber, "csv") orElse findPlan(iterationDir, iterationNumber, "xml")
    } yield plansPath
    plansPaths.headOption
  }

  import collection.JavaConverters._
  private def findDirs(parentDir: Path, prefix: String) =
    Files
      .find(parentDir, 1, (path: Path, attr) => attr.isDirectory && path.getFileName.toString.startsWith(prefix))
      .collect(Collectors.toList[Path])
      .asScala
}
