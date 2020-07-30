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
      PersonId(inputPersonInfo.personId),
      HouseholdId(inputPersonInfo.householdId),
      0,
      inputPersonInfo.age,
      inputPersonInfo.sex.isFemale,
      income.toDouble,
      industry = None
    )
  }
}
