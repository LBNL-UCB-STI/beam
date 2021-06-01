package beam.utils.scenario.urbansim.censusblock.merger

import beam.sim.population.PopulationAdjustment
import beam.utils.scenario.urbansim.censusblock.entities.{Block, InputHousehold}
import beam.utils.scenario.{HouseholdId, HouseholdInfo}

class HouseholdMerger(blocks: Map[Long, Block]) extends Merger[InputHousehold, HouseholdInfo] {
  override def merge(iter: Iterator[InputHousehold]): Iterator[HouseholdInfo] = iter.map(inputToOutput)

  private def inputToOutput(inputHousehold: InputHousehold): HouseholdInfo = {
    val hhBlockId = inputHousehold.blockId.toLong
    val block = blocks.get(hhBlockId) match {
      case Some(value) => value
      case None =>
        throw new NoSuchElementException(s"There are no block with blockId $hhBlockId.")
    }
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
