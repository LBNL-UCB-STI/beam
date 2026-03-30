package beam.utils.scenario.urbansim.censusblock.merger

import beam.sim.population.PopulationAdjustment
import beam.utils.scenario.urbansim.censusblock.entities.{InputHousehold, InputPersonInfo}
import beam.utils.scenario.{HouseholdId, PersonId, PersonInfo}

class PersonMerger(inputHousehold: Map[String, InputHousehold]) extends Merger[InputPersonInfo, PersonInfo] {
  override def merge(iter: Iterator[InputPersonInfo]): Iterator[PersonInfo] = iter.map(inputToOutput)

  private def inputToOutput(inputPersonInfo: InputPersonInfo): PersonInfo = {
    val valueOfTime = inputPersonInfo.valueOfTime.getOrElse(
      PopulationAdjustment.incomeToValueOfTime(inputHousehold(inputPersonInfo.householdId).income).getOrElse(0d)
    )

    PersonInfo(
      personId = PersonId(inputPersonInfo.personId),
      householdId = HouseholdId(inputPersonInfo.householdId),
      rank = 0,
      age = inputPersonInfo.age,
      excludedModes = Seq.empty,
      rideHailServiceSubscription = Seq.empty,
      isFemale = inputPersonInfo.sex.isFemale,
      valueOfTime = valueOfTime,
      industry = inputPersonInfo.industry
    )
  }
}
