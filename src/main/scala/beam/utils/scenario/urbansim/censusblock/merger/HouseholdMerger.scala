package beam.utils.scenario.urbansim.censusblock.merger

import beam.sim.population.PopulationAdjustment
import beam.utils.scenario.urbansim.censusblock.entities.{Block, InputHousehold}
import beam.utils.scenario.{HouseholdId, HouseholdInfo}

class HouseholdMerger(blocks: Map[String, Block]) extends Merger[InputHousehold, HouseholdInfo] {
  override def merge(iter: Iterator[InputHousehold]): Iterator[HouseholdInfo] = iter.map(inputToOutput)

  private def inputToOutput(inputHousehold: InputHousehold): HouseholdInfo = {
    val block = blocks(inputHousehold.blockId)
    val income = PopulationAdjustment.incomeToValueOfTime(inputHousehold.income).getOrElse(0d)

    HouseholdInfo(
      HouseholdId(inputHousehold.householdId),
      inputHousehold.cars,
      income,
      block.x,
      block.y
    )
  }

}
