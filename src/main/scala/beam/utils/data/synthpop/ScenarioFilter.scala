package beam.utils.data.synthpop

import java.io.{Closeable, File}

import beam.utils.csv.GenericCsvReader
import beam.utils.scenario.generic.readers.{CsvHouseholdInfoReader, CsvPersonInfoReader, CsvPlanElementReader}
import beam.utils.scenario.generic.writers.{CsvHouseholdInfoWriter, CsvPersonInfoWriter, CsvPlanElementWriter}
import beam.utils.scenario.{PersonId, PlanElement}
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._
import scala.collection.mutable

object ScenarioFilter extends LazyLogging {

  def main(args: Array[String]): Unit = {
    val input = "/mnt/data/work/beam/beam-new-york/test/input/newyork/generic_scenario/10034k"
    val outputBase = "/mnt/data/work/beam/beam-new-york/test/input/newyork/generic_scenario/6853k-NYC-related"

    // https://beam-outputs.s3.us-east-2.amazonaws.com/new_city/fips_codes.csv
    val fipsCodes = "/home/nikolay/.jupyter-files/fips_codes.csv"

    val output = outputBase + SimpleScenarioGenerator.getCurrentDateTime

    val counties =
      Seq("Bronx County NY", "Kings County NY", "New York County NY", "Queens County NY", "Richmond County NY")

    filterScenario(input, output, fipsCodes, counties)
  }

  def filterScenario(
    pathToInput: String,
    pathToOutput: String,
    pathToFipsCodes: String,
    selectedCounties: Seq[String]
  ): Unit = {

    require(new File(pathToOutput).mkdirs(), s"${pathToOutput} exists, stopping...")

    val stateToCountyToName = readFipsCodes(pathToFipsCodes)
    logger.info(s"read ${stateToCountyToName.size} states from FIPS source $pathToFipsCodes")

    logger.info(s"Source scenario path: $pathToInput")
    logger.info(s"Output path: $pathToOutput")
    logger.info(s"Selected counties: ${selectedCounties.mkString(",")}")

    def getCountyNameInLowerCase(geoId: String): String = {
      try {
        val state = geoId.slice(0, 2).toInt
        val county = geoId.slice(3, 6).toInt

        stateToCountyToName.get(state) match {
          case None =>
            logger.error(s"Can't find state $state information in map build from $pathToFipsCodes")
            "??"
          case Some(countyToName) =>
            countyToName.get(county) match {
              case None =>
                logger.error(s"Can't find state:county $state:$county information in map build from $pathToFipsCodes")
                "??"
              case Some(name) => name.toLowerCase
            }
        }
      } catch {
        case e: Throwable =>
          logger.error(s"Got $e while trying to read state and county from geoId $geoId")
          "???"
      }
    }

    val planElements: Array[PlanElement] = CsvPlanElementReader.read(s"$pathToInput/plans.csv.gz")
    logger.info(s"Read ${planElements.length} plans.")

    val personToCounties = planElements.foldLeft(mutable.HashMap.empty[PersonId, mutable.HashSet[String]]) {
      case (personToCounties, plan) if plan.geoId.isDefined =>
        val county = getCountyNameInLowerCase(plan.geoId.get)
        personToCounties.get(plan.personId) match {
          case Some(counties) => counties += county
          case None           => personToCounties(plan.personId) = mutable.HashSet[String](county)
        }
        personToCounties

      case (personToCounties, _) => personToCounties
    }
    logger.info(s"Got ${personToCounties.size} persons from plans.")

    case class Accumulator(
      selectedPersons: mutable.HashSet[PersonId] = mutable.HashSet.empty[PersonId],
      counties: mutable.HashSet[String] = mutable.HashSet.empty[String]
    )

    val countiesSet = selectedCounties.map(_.toLowerCase).toSet

    val Accumulator(selectedPersonsIds, counties) = personToCounties.foldLeft(Accumulator()) {
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

    val plansFilePath = s"$pathToOutput/plans.csv.gz"
    CsvPlanElementWriter.write(plansFilePath, planElements.filter(p => selectedPersonsIds.contains(p.personId)))
    logger.info(s"Wrote plans information to $plansFilePath")

    val persons = CsvPersonInfoReader.read(s"$pathToInput/persons.csv.gz")
    logger.info(s"Read ${persons.length} persons")
    val selectedPersons = persons.filter(p => selectedPersonsIds.contains(p.personId))
    val selectedHHIds = selectedPersons.map(p => p.householdId).toSet
    logger.info(s"Got ${selectedHHIds.size} selected households")

    val personsFilePath = s"$pathToOutput/persons.csv.gz"
    CsvPersonInfoWriter.write(personsFilePath, selectedPersons)
    logger.info(s"Wrote persons information to $personsFilePath")

    val households = CsvHouseholdInfoReader.read(s"$pathToInput/households.csv.gz")
    logger.info(s"Read ${households.length} households")
    val householdFilePath = s"$pathToOutput/households.csv.gz"
    CsvHouseholdInfoWriter.write(householdFilePath, households.filter(hh => selectedHHIds.contains(hh.householdId)))
    logger.info(s"Wrote households information to $householdFilePath")
  }

  def readFipsCodes(pathToFipsCodes: String): Map[Int, Map[Int, String]] = {
    object FipsRow {
      def apply(csvRow: java.util.Map[String, String]): FipsRow = {
        def getStr(key: String): String =
          if (csvRow.containsKey(key)) csvRow.get(key)
          else {
            logger.error(s"FIPS codes file missing '$key' value. Using '??' as replacement.")
            logger.error(s"The row: ${csvRow.asScala.mkString(",")}")
            "??"
          }

        def getInt(key: String): Int =
          try { getStr(key).toInt } catch {
            case _: java.lang.NumberFormatException =>
              logger.error(s"Can't parse ${getStr(key)} as Int. Got: java.lang.NumberFormatException")
              logger.error(s"The row: ${csvRow.asScala.mkString(",")}")
              -1
            case e: Throwable =>
              logger.error(s"Can't parse ${getStr(key)} as Int. Got: $e")
              logger.error(s"The row: ${csvRow.asScala.mkString(",")}")
              throw e
          }

        new FipsRow(getInt("state"), getInt("county"), getStr("long_name"))
      }
    }

    case class FipsRow(state: Int, county: Int, name: String) {}

    def readFipsRows(relativePath: String): Map[Int, Map[Int, String]] = {
      val (iter: Iterator[FipsRow], toClose: Closeable) =
        GenericCsvReader.readAs[FipsRow](relativePath, FipsRow.apply, _ => true)
      try {
        iter
          .foldLeft(mutable.Map.empty[Int, mutable.Map[Int, String]]) {
            case (stateToCountyToName, fipsRow) =>
              val countyToName = stateToCountyToName.getOrElse(fipsRow.state, mutable.Map.empty[Int, String])
              countyToName(fipsRow.county) = fipsRow.name
              stateToCountyToName(fipsRow.state) = countyToName
              stateToCountyToName
          }
          .map { case (key, mutmap) => key -> mutmap.toMap }
          .toMap
      } finally {
        toClose.close()
      }
    }

    readFipsRows(pathToFipsCodes)
  }
}
