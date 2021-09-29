package beam.utils.scenario

import beam.utils.scenario.generic.readers.{CsvPlanElementReader, XmlPlanElementReader}
import com.typesafe.scalalogging.LazyLogging

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.stream.Collectors
import scala.collection.mutable
import scala.util.{Random, Try}

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
  require(0 <= fraction && fraction <= 1.0, "fraction must be in [0, 1]")

  def merge(plans: Iterable[PlanElement]): (Iterable[PlanElement], Boolean) = {
    if (fraction <= 0) {
      return plans -> false
    }

    LastRunOutputSource.findLastRunOutputPlans(outputDir, dirPrefix) match {
      case Some(planPath) =>
        logger.info("Found the plans in the beam output directory: {}", planPath)
        val previousPlans = if (planPath.getFileName.toString.toLowerCase.contains(".csv")) {
          CsvPlanElementReader.read(planPath.toString)
        } else {
          XmlPlanElementReader.read(planPath.toString)
        }
        val convertedPlans = previousPlans.map(adjustForScenario)
        PreviousRunPlanMerger.merge(convertedPlans, plans, fraction, rnd) -> true
      case None =>
        logger.warn(
          "Not found appropriate output plans in the beam output directory: {}, dirPrefix = {}",
          outputDir,
          dirPrefix
        )
        plans -> false
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

object LastRunOutputSource extends LazyLogging {

  def findLastRunOutputPlans(outputPath: Path, dirPrefix: String): Option[Path] = {
    val plansPaths = for {
      (itDir, itNumber) <- findAllLastIterationDirectories(outputPath, dirPrefix)
      plansPath         <- findFile(itDir, itNumber, "plans.csv.gz") orElse findFile(itDir, itNumber, "plans.xml.gz")
    } yield plansPath
    plansPaths.headOption
  }

  def findLastRunLinkStats(outputPath: Path, dirPrefix: String): Option[Path] = {
    val paths = for {
      (itDir, itNumber) <- findAllLastIterationDirectories(outputPath, dirPrefix)
      linkStatsPath     <- findFile(itDir, itNumber, "linkstats.csv.gz")
    } yield linkStatsPath
    paths.headOption
  }

  private def findFile(iterationDir: Path, iterationNumber: Int, fileName: String): Option[Path] = {
    val filePath = iterationDir.resolve(s"$iterationNumber.$fileName")
    Some(filePath).filter(Files.exists(_))
  }

  private def findAllLastIterationDirectories(outputPath: Path, dirPrefix: String) = {
    val IterationNumber = """it.(\d+)""".r
    for {
      outputDir <- findDirs(outputPath, dirPrefix)
        .filter(path => Files.exists(path.resolve("ITERS")))
        .sortWith((path1, path2) => path1.getFileName.toString.compareTo(path2.getFileName.toString) > 0)
        .view
      itDirAndNumber <- findDirs(outputDir.resolve("ITERS"), "it.")
        .flatMap(itPath =>
          itPath.getFileName.toString match {
            case IterationNumber(num) => Some(itPath -> num.toInt)
            case _                    => None
          }
        )
        .sortBy { case (_, itNumber) => -itNumber }
        .view
    } yield itDirAndNumber
  }

  import collection.JavaConverters._

  private def findDirs(parentDir: Path, prefix: String) =
    Try {
      Files
        .find(parentDir, 1, (path: Path, attr) => attr.isDirectory && path.getFileName.toString.startsWith(prefix))
        .collect(Collectors.toList[Path])
        .asScala
    }.recover { case e: IOException =>
      logger.warn("Failed to find parent dir. {}", parentDir, e)
      mutable.Buffer.empty[Path]
    }.get
}
