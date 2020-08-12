package beam.utils.data.synthpop.tools

import beam.utils.ProfilingUtils
import beam.utils.scenario.generic.readers.{CsvHouseholdInfoReader, CsvPersonInfoReader, CsvPlanElementReader}
import beam.utils.scenario.generic.writers.{CsvHouseholdInfoWriter, CsvPersonInfoWriter, CsvPlanElementWriter}

import scala.util.Random

class ScenarioDownsampler {}

object ScenarioDownsampler {

  def main(args: Array[String]): Unit = {
    val newScenarioSize: Int = 1000000
    val margin: Double = 0.05
    val seed: Int = 42

    val pathToScenarioFolder: String = "test/input/newyork/generic_scenario/6853k-NYC-related"
    val pathToHouseholds = s"$pathToScenarioFolder/households.csv.gz"
    val pathToPersons = s"$pathToScenarioFolder/persons.csv.gz"
    val pathToPlans = s"$pathToScenarioFolder/plans.csv.gz"

    val pathToOutput: String = "test/input/newyork/generic_scenario/1051k-NYC-related"

    ProfilingUtils.timed(s"Downsample scenario to $newScenarioSize with seed $seed", println) {

      val households = CsvHouseholdInfoReader.read(pathToHouseholds)
      println(s"households: ${households.length}")

      val persons = CsvPersonInfoReader.read(pathToPersons)
      println(s"persons: ${persons.length}")
      val personToHouseholdRatio = persons.length.toDouble / households.length
      println(s"Persons to Households ratio: $personToHouseholdRatio")

      val approxNumberOfHouseholds = newScenarioSize / personToHouseholdRatio
      val withMargin = (approxNumberOfHouseholds + approxNumberOfHouseholds * margin).toInt
      println(s"approxNumberOfHouseholds $approxNumberOfHouseholds")
      println(s"withMargin $margin: $withMargin")

      val selectedHouseholds = new Random(seed).shuffle(households.toIterator).take(withMargin).toSet
      println(s"selectedHouseholds: ${selectedHouseholds.size}")
      CsvHouseholdInfoWriter.write(pathToOutput + "/households.csv.gz", selectedHouseholds.toIterator)

      val selectedHouseholdIds = selectedHouseholds.map(_.householdId.id)
      println(s"selectedHouseholdIds: ${selectedHouseholdIds.size}")

      val selectedPersons = persons.filter(p => selectedHouseholdIds.contains(p.householdId.id))
      println(s"selectedPersons: ${selectedPersons.length}")
      println(
        s"Selected Persons to Selected Households ratio: ${selectedPersons.length.toDouble / selectedHouseholdIds.size} "
      )
      CsvPersonInfoWriter.write(pathToOutput + "/persons.csv.gz", selectedPersons.toIterator)

      val selectedPersonIds = selectedPersons.map(_.personId.id).toSet

      val csvPlanWriter = new CsvPlanElementWriter(pathToOutput + "/plans.csv.gz")
      var nSelectedPlans: Int = 0
      try {
        val (planIt, toClose) = CsvPlanElementReader.readWithFilter(
          pathToPlans,
          planElement => selectedPersonIds.contains(planElement.personId.id)
        )
        try {
          planIt.foreach { plan =>
            csvPlanWriter.write(Iterator.single(plan))
            nSelectedPlans += 1
          }
        } finally {
          toClose.close()
        }
      } finally {
        csvPlanWriter.close()
      }

      println(s"Number of selected plans: $nSelectedPlans")

      println("Done")
    }
  }
}
