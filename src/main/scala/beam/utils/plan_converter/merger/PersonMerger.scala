package beam.utils.plan_converter.merger

import beam.sim.population.PopulationAdjustment
import beam.utils.plan_converter.entities.{InputHousehold, InputPersonInfo}
import beam.utils.scenario.urbansim.DataExchange.PersonInfo

class PersonMerger(inputHousehold: Map[Int, InputHousehold]) extends Merger[InputPersonInfo, PersonInfo]{
  override def merge(iter: Iterator[InputPersonInfo]): Iterator[PersonInfo] = new Iterator[PersonInfo]{
    override def hasNext: Boolean = iter.hasNext

    override def next(): PersonInfo = inputToOutput(iter.next())
  }

  private def inputToOutput(inputPersonInfo: InputPersonInfo): PersonInfo = {
    val inputIncome = inputHousehold(inputPersonInfo.householdId).income
    val income = PopulationAdjustment.IncomeToValueOfTime(inputIncome).getOrElse{
      throw new IllegalStateException(s"Can't compute income with input value income $inputIncome")
    }

    PersonInfo(
      inputPersonInfo.personId.toString,
      inputPersonInfo.householdId.toString,
      0,
      inputPersonInfo.age,
      inputPersonInfo.sex.isFemale,
      income.toDouble
    )
  }
}
