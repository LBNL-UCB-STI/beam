package beam.utils.data.synthpop

import beam.utils.data.synthpop.models.Models
import com.typesafe.scalalogging.StrictLogging

object PopulationCorrection extends StrictLogging {

  def adjust(
    input: Seq[(Models.Household, Seq[Models.Person])],
    stateCodeToWorkForceSampler: Map[String, WorkForceSampler]
  ): Map[Models.Household, Seq[Models.Person]] = {
    // Take only with age is >= 16
    val elderThan16Years = input
      .map { case (hh, persons) =>
        hh -> persons.filter(p => p.age >= 16)
      }
      .filter { case (_, persons) => persons.nonEmpty }
      .toMap
    val removedHh = input.size - elderThan16Years.size
    val removedPeopleYoungerThan16 = input.map(x => x._2.size).sum - elderThan16Years.values.map(x => x.size).sum
    logger.info(s"Read ${input.size} households with ${input.map(x => x._2.size).sum} people")
    logger.info(s"""After filtering them got ${elderThan16Years.size} households with ${elderThan16Years.values
      .map(x => x.size)
      .sum} people.
         |Removed $removedHh households and $removedPeopleYoungerThan16 people who are younger than 16""".stripMargin)

    //    showAgeCounts(elderThan16Years)

    val finalResult = elderThan16Years.foldLeft(Map[Models.Household, Seq[Models.Person]]()) {
      case (acc, (hh: Models.Household, people)) =>
        val workForceSampler = stateCodeToWorkForceSampler(hh.geoId.state.value)
        val workers = people.collect { case person if workForceSampler.isWorker(person.age) => person }
        if (workers.isEmpty) acc
        else {
          acc + (hh -> workers)
        }
    }
    val removedEmptyHh = elderThan16Years.size - finalResult.size
    val removedNonWorkers = elderThan16Years.map(x => x._2.size).sum - finalResult.values.map(x => x.size).sum
    logger.info(s"""After applying work force sampler got ${finalResult.size} households with ${finalResult.values
      .map(x => x.size)
      .sum} people.
         |Removed $removedEmptyHh households and $removedNonWorkers people""".stripMargin)

    finalResult
  }
}
