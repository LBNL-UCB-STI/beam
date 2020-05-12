package beam.utils.plan_converter.merger

import beam.sim.population.PopulationAdjustment
import beam.utils.plan_converter.entities.{InputHousehold, InputPersonInfo}
import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo}

class PersonMerger(inputHousehold: Map[String, InputHousehold]) extends Merger[InputPersonInfo, PersonInfo]{
  override def merge(iter: Iterator[InputPersonInfo]): Iterator[PersonInfo] = iter.map(inputToOutput)

  private def inputToOutput(inputPersonInfo: InputPersonInfo): PersonInfo = {
    val inputIncome = inputHousehold(inputPersonInfo.householdId).income
    val income = PopulationAdjustment.IncomeToValueOfTime(inputIncome).getOrElse{
      throw new IllegalStateException(s"Can't compute income with input value income $inputIncome")
    }

    PersonInfo(
      PersonId(inputPersonInfo.personId),
      HouseholdId(inputPersonInfo.householdId),
      0,
      inputPersonInfo.age,
      inputPersonInfo.sex.isFemale,
      income.toDouble
    )
  }
}
