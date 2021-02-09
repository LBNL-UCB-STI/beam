package beam.utils.data.synthpop

import java.io.{Closeable, File}

import beam.utils.ProfilingUtils
import beam.utils.scenario.generic.readers.{CsvHouseholdInfoReader, CsvPersonInfoReader, CsvPlanElementReader}
import beam.utils.scenario.generic.writers.{CsvHouseholdInfoWriter, CsvPersonInfoWriter, CsvPlanElementWriter}
import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo, PlanElement}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.mutable
import scala.util.Try

private case class InputPath(path: String) extends AnyVal
private case class OutputPath(path: String) extends AnyVal

object NewYorkScenarioFilter extends LazyLogging {

  def main(args: Array[String]): Unit = {
    val input = InputPath("C:/repos/beam/test/input/newyork/generic_scenario/10034k")
    val outputBase = "C:/repos/beam/test/input/newyork/generic_scenario/New"

    // https://beam-outputs.s3.us-east-2.amazonaws.com/new_city/fips_codes.csv
    val fipsCodes = "C:/Users/User/Downloads/fips_codes.csv"

    val output = OutputPath(outputBase + SimpleScenarioGenerator.getCurrentDateTime)

    val counties =
      Seq("Bronx County NY", "Kings County NY", "New York County NY", "Queens County NY", "Richmond County NY")

    filterScenario(input, output, fipsCodes, counties)
  }

  def filterScenario(
    pathToInput: InputPath,
    pathToOutput: OutputPath,
    pathToFipsCodes: String,
    selectedCounties: Seq[String]
  ): Unit = {

    require(new File(pathToOutput.path).mkdirs(), s"${pathToOutput} exists, stopping...")

    val fipsCodes: FipsCodes = FipsCodes(pathToFipsCodes)

    logger.info(s"read ${fipsCodes.stateToCountyToName.size} states from FIPS source $pathToFipsCodes")

    logger.info(s"Source scenario path: $pathToInput")
    logger.info(s"Output path: $pathToOutput")
    logger.info(s"Selected counties: ${selectedCounties.mkString(",")}")

    val personToCounties: collection.Map[PersonId, collection.Set[String]] = buildPersonToCounties(
      s"${pathToInput.path}/plans.csv.gz",
      fipsCodes
    )
    logger.info(s"Got ${personToCounties.size} persons from plans.")

    case class Accumulator(
      selectedPersons: mutable.HashSet[PersonId] = mutable.HashSet.empty[PersonId],
      counties: mutable.HashSet[String] = mutable.HashSet.empty[String]
    )

    val countiesSet = selectedCounties.map(_.toLowerCase).toSet

    val Accumulator(selectedPersonsIds: scala.collection.Set[PersonId], counties) =
      personToCounties.foldLeft(Accumulator()) {
        case (accum, (personId, counties)) =>
          if (counties.intersect(countiesSet).nonEmpty) {
            accum.selectedPersons += personId
          } else {
            accum.counties ++= counties
          }
          accum
      }

    val filteredOutCounties = counties -- selectedCounties
    logger.info(
      s"Following counties are from activities but were not in the list of selected counties: ${filteredOutCounties.mkString(", ")}"
    )
    logger.info(
      s"Got ${selectedPersonsIds.size} persons who has original or destination location in selected counties."
    )
    require(selectedPersonsIds.nonEmpty, "There are 0 people selected, probably there is something wrong with data. ")

    val industryToPersons: collection.Map[String, collection.Set[PersonId]] =
      buildIndustryToPeople(pathToInput, selectedPersonsIds)

    logger.info(s"Industry details:")
    industryToPersons.toSeq.sortBy(x => x._2.size)(Ordering[Int].reverse).foreach {
      case (industry, people) =>
        logger.info(s"$industry: ${people.size}")
    }

    ProfilingUtils.timed(s"Write filtered plans", x => logger.info(x)) {
      filterPlans(selectedPersonsIds, industryToPersons, pathToInput, pathToOutput)
    }

    val selectedHHIds: collection.Set[HouseholdId] =
      ProfilingUtils.timed("Write filtered people", x => logger.info(x)) {
        filterPeople(selectedPersonsIds, pathToInput, pathToOutput)
      }

    ProfilingUtils.timed("Write filtered households", x => logger.info(x)) {
      filterHouseholds(selectedHHIds, pathToInput, pathToOutput)
    }

  }

  def filterPlans(
    selectedPersonsIds: scala.collection.Set[PersonId],
    industryToPersons: collection.Map[String, collection.Set[PersonId]],
    pathToInput: InputPath,
    pathToOutput: OutputPath
  ): Unit = {
    val (planElements: Iterator[PlanElement], toClose: Closeable) =
      CsvPlanElementReader.readWithFilter(s"${pathToInput.path}/plans.csv.gz", _ => true)
    val outputPlansFilePath = s"${pathToOutput.path}/plans.csv.gz"
    val csvPlanWriter = new CsvPlanElementWriter(outputPlansFilePath)
    val nonWorkers = industryToPersons.getOrElse("not employed", Set.empty)
    try {
      var totalWrittenPlans: Int = 0
      planElements.foreach { plan =>
        val isPlanOfSelectedPerson = selectedPersonsIds.contains(plan.personId)
        val isNonWorker = nonWorkers.contains(plan.personId)
        val isWorker = !isNonWorker
        val isFirstElement = plan.planElementIndex == 1
        val shouldSelect = isPlanOfSelectedPerson && (isWorker || (isNonWorker && isFirstElement))
        if (shouldSelect) {
          val updatedPlan = if (isNonWorker && isFirstElement) plan.copy(activityEndTime = None) else plan
          csvPlanWriter.write(Iterator.single(updatedPlan))
          totalWrittenPlans += 1
        }
        shouldSelect
      }
      logger.info(s"Wrote $totalWrittenPlans plans information to $outputPlansFilePath")
    } finally {
      Try(csvPlanWriter.close())
      Try(toClose.close())
    }

  }

  def filterPeople(
    selectedPersonsIds: scala.collection.Set[PersonId],
    pathToInput: InputPath,
    pathToOutput: OutputPath
  ): scala.collection.Set[HouseholdId] = {
    val (persons: Iterator[PersonInfo], toClose) =
      CsvPersonInfoReader.readWithFilter(s"${pathToInput.path}/persons.csv.gz", _ => true)
    val filteredPersonsFilePath = s"${pathToOutput.path}/persons.csv.gz"
    val csvPersonInfoWriter = new CsvPersonInfoWriter(filteredPersonsFilePath)
    val selectedHHIds: mutable.Set[HouseholdId] = mutable.Set[HouseholdId]()

    try {
      var totalWrittenPeople: Int = 0
      persons.filter(p => selectedPersonsIds.contains(p.personId)).foreach { person =>
        csvPersonInfoWriter.write(Iterator.single(person))
        selectedHHIds += person.householdId
        totalWrittenPeople += 1
      }
      logger.info(
        s"Written $totalWrittenPeople people. Total number of households for those people: ${selectedHHIds.size}"
      )
      selectedHHIds
    } finally {
      Try(toClose.close())
      Try(csvPersonInfoWriter.close())
    }
  }

  def filterHouseholds(
    selectedHHIds: collection.Set[HouseholdId],
    pathToInput: InputPath,
    pathToOutput: OutputPath
  ): Unit = {
    val filteredHouseholdFilePath = s"${pathToOutput.path}/households.csv.gz"
    val (households, toClose) =
      CsvHouseholdInfoReader.readWithFilter(s"${pathToInput.path}/households.csv.gz", _ => true)
    val csvHouseholdInfoWriter = new CsvHouseholdInfoWriter(filteredHouseholdFilePath)
    try {
      var totalWrittenHouseholds: Int = 0
      households.filter(hh => selectedHHIds.contains(hh.householdId)).foreach { household =>
        csvHouseholdInfoWriter.write(Iterator.single(household))
        totalWrittenHouseholds += 1
      }
      logger.info(s"Wrote $totalWrittenHouseholds households information to $filteredHouseholdFilePath")
    } finally {
      csvHouseholdInfoWriter.close()
      toClose.close()
    }
  }

  private def buildPersonToCounties(
    pathToPlans: String,
    fipsCodes: FipsCodes
  ): collection.Map[PersonId, collection.Set[String]] = {
    // The geoid from activity is enough, no need to read leg
    val (it: Iterator[PlanElement], toClose: Closeable) =
      CsvPlanElementReader.readWithFilter(pathToPlans, x => x.planElementType.equalsIgnoreCase("activity"))

    try {
      it.foldLeft(mutable.HashMap.empty[PersonId, mutable.HashSet[String]]) {
        case (personToCounties, plan) if plan.geoId.isDefined =>
          val county = fipsCodes.getCountyNameInLowerCase(plan.geoId.get)
          personToCounties.get(plan.personId) match {
            case Some(counties) => counties += county
            case None           => personToCounties(plan.personId) = mutable.HashSet[String](county)
          }
          personToCounties

        case (personToCounties, _) => personToCounties
      }
    } finally {
      toClose.close()
    }
  }

  private def buildIndustryToPeople(
    pathToInput: InputPath,
    selectedPersonsIds: scala.collection.Set[PersonId]
  ): collection.Map[String, collection.Set[PersonId]] = {
    val (persons: Iterator[PersonInfo], toClose) =
      CsvPersonInfoReader.readWithFilter(
        s"${pathToInput.path}/persons.csv.gz",
        p => selectedPersonsIds.contains(p.personId)
      )

    try {
      persons.foldLeft(mutable.HashMap.empty[String, mutable.HashSet[PersonId]]) {
        case (acc, person) =>
          val industry = person.industry.getOrElse("")
          acc.get(industry) match {
            case Some(people) => people += person.personId
            case None         => acc.put(industry, mutable.HashSet[PersonId](person.personId))
          }
          acc
      }
    } finally {
      toClose.close()
    }
  }
}
