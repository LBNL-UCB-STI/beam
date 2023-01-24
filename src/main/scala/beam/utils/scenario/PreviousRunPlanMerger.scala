package beam.utils.scenario

import beam.utils.scenario.generic.readers.{CsvPlanElementReader, XmlPlanElementReader}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.network.Network

import java.io.IOException
import java.nio.file.{Files, Path}
import java.util.stream.Collectors
import scala.collection.mutable
import scala.util.{Random, Try}

/**
  * @author Dmitry Openkov
  */
class PreviousRunPlanMerger(
  fractionOfNewPlansToUpdate: Double,
  sampleFraction: Double,
  maximumNumberOfPlansToKeep: Option[Int],
  outputDir: Path,
  dirPrefix: String,
  rnd: Random,
  adjustForScenario: PlanElement => PlanElement,
  network: Option[Network] = None
) extends LazyLogging {
  require(0 <= fractionOfNewPlansToUpdate && fractionOfNewPlansToUpdate <= 1.0, "fraction must be in [0, 1]")

  def merge(plans: Iterable[PlanElement]): (Iterable[PlanElement], Boolean) = {
    if (fractionOfNewPlansToUpdate <= 0) {
      return plans -> false
    }

    LastRunOutputSource.findLastRunOutputPlans(outputDir, dirPrefix) match {
      case Some(planPath) =>
        logger.info("Found the plans in the beam output directory: {}", planPath)
        val previousPlans = if (planPath.getFileName.toString.toLowerCase.contains(".csv")) {
          CsvPlanElementReader.read(planPath.toString)
        } else {
          XmlPlanElementReader.read(planPath.toString, network)
        }
        val convertedPlans = previousPlans.map(adjustForScenario)
        PreviousRunPlanMerger.merge(
          convertedPlans,
          plans,
          fractionOfNewPlansToUpdate,
          sampleFraction,
          maximumNumberOfPlansToKeep,
          rnd
        ) -> true
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
    fractionOfPlansToUpdate: Double,
    populationSample: Double,
    maximumNumberOfPlansToKeep: Option[Int],
    random: Random
  ): Iterable[PlanElement] = {
    val persons = plans.map(_.personId).toSet
    val mergePersons = plansToMerge.map(_.personId).toSet
    val matchedPersons = persons & mergePersons
    val numberToReplace = (persons.size * fractionOfPlansToUpdate).round.toInt
    val personIdsToReplace = random.shuffle(matchedPersons.toSeq).take(numberToReplace).toSet
    val newPersons = mergePersons &~ persons
    val numberToAdd = (newPersons.size * populationSample).round.toInt
    val personIdsToAdd = random.shuffle(newPersons.toSeq).take(numberToAdd).toSet
    logger.info(
      "Creating {} new people and adding new plans to {} people",
      personIdsToAdd.size,
      personIdsToReplace.size
    )
    val shouldReplace = (plan: PlanElement) => personIdsToReplace.contains(plan.personId)
    val (oldToBeReplaced, oldElementsToKeep) = plans.partition(shouldReplace)
    val elementsFromExistingPersonsToAdd =
      plansToMerge.filter(shouldReplace).map(_.copy(planSelected = true, planIndex = 0))
    val shouldAdd = (plan: PlanElement) => personIdsToAdd.contains(plan.personId)
    val elementsFromNewPersonsToAdd = plansToMerge.filter(shouldAdd).map(_.copy(planSelected = true, planIndex = 0))
    val unselectedPlanElements = {
      oldToBeReplaced.groupBy(_.personId).flatMap { case (_, elements) =>
        elements
          .groupBy(_.planIndex)
          .toList
          .sortBy { case (_, elements) => -elements.head.planScore }
          .zipWithIndex
          .take(maximumNumberOfPlansToKeep.getOrElse(0))
          .flatMap { case ((_, elems), idx) =>
            elems.map { case elem =>
              elem.copy(planIndex = idx + 1, planSelected = false)
            }
          }
      }
    }

    oldElementsToKeep ++ unselectedPlanElements ++ elementsFromExistingPersonsToAdd ++ elementsFromNewPersonsToAdd
  }
}

object LastRunOutputSource extends LazyLogging {

  def findLastRunOutputPlans(outputPath: Path, dirPrefix: String): Option[Path] = {
    val plansPaths = for {
      (itDir, itNumber) <- findAllLastIterationDirectories(outputPath, dirPrefix)
      plansPath <- findLatestOutputDirectory(outputPath, dirPrefix)
        .filter { p =>
          val outputPlansLocation = p.resolve("experienced_plans.xml.gz")
          logger.info("Initially looking for plans at {}", outputPlansLocation.toString)
          Files.exists(outputPlansLocation)
        }
        .map(_.resolve("experienced_plans.xml.gz")) orElse findFile(
        itDir,
        itNumber,
        "experienced_plans.xml.gz"
      ) orElse findFile(itDir, itNumber, "plans.csv.gz")
    } yield plansPath
    plansPaths.headOption
  }

  def findLastRunLinkStats(outputPath: Path, dirPrefix: String, initialLinkstatsPath: Option[Path]): Option[Path] = {
    val paths = for {
      (itDir, itNumber) <- findAllLastIterationDirectories(outputPath, dirPrefix)
      linkStatsPath     <- findFile(itDir, itNumber, "linkstats.csv.gz")
    } yield linkStatsPath
    (paths.headOption, initialLinkstatsPath) match {
      case (Some(path), _)        => Some(path)
      case (_, Some(defaultPath)) => Some(defaultPath)
      case _                      => None
    }
  }

  private def findFile(iterationDir: Path, iterationNumber: Int, fileName: String): Option[Path] = {
    val filePath = iterationDir.resolve(s"$iterationNumber.$fileName")
    Some(filePath).filter(Files.exists(_))
  }

  private def findLatestOutputDirectory(outputPath: Path, dirPrefix: String) = {
    findDirs(outputPath, dirPrefix)
      .filter(path => Files.exists(path.resolve("ITERS")))
      .sortWith((path1, path2) => path1.getFileName.toString.compareTo(path2.getFileName.toString) > 0)
      .headOption
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
