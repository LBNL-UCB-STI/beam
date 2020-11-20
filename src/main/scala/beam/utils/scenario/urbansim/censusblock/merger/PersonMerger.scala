package beam.utils.scenario.urbansim.censusblock.merger

import beam.sim.population.PopulationAdjustment
import beam.utils.scenario.urbansim.censusblock.entities.{InputHousehold, InputPersonInfo}
import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo}

class PersonMerger(inputHousehold: Map[String, InputHousehold]) extends Merger[InputPersonInfo, PersonInfo] {
  override def merge(iter: Iterator[InputPersonInfo]): Iterator[PersonInfo] = iter.map(inputToOutput)

  private def inputToOutput(inputPersonInfo: InputPersonInfo): PersonInfo = {
    val inputIncome = inputHousehold(inputPersonInfo.householdId).income
    val income = PopulationAdjustment.incomeToValueOfTime(inputIncome).getOrElse(0d)

    PersonInfo(
      personId = PersonId(inputPersonInfo.personId),
      householdId = HouseholdId(inputPersonInfo.householdId),
      rank = 0,
      age = inputPersonInfo.age,
      excludedModes = Seq.empty,
      isFemale = inputPersonInfo.sex.isFemale,
      valueOfTime = income.toDouble
    )
  }
}
