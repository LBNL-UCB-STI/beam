package beam.utils.plan_converter.merger

import beam.sim.population.PopulationAdjustment
import beam.utils.plan_converter.entities.{Block, InputHousehold}
import beam.utils.scenario.{HouseholdId, HouseholdInfo}

class HouseholdMerger(blocks: Map[String, Block]) extends Merger[InputHousehold, HouseholdInfo] {
  override def merge(iter: Iterator[InputHousehold]): Iterator[HouseholdInfo] = iter.map(inputToOutput)

  private def inputToOutput(inputHousehold: InputHousehold): HouseholdInfo = {
    val block = blocks(inputHousehold.blockId)
    val income = PopulationAdjustment.IncomeToValueOfTime(inputHousehold.income).getOrElse{
      throw new IllegalStateException(s"Can't compute income with input value income ${inputHousehold.income}")
    }

    HouseholdInfo(
      HouseholdId(inputHousehold.householdId),
      inputHousehold.cars,
      income,
      block.x,
      block.y
    )
  }

}
