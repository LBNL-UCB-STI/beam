package beam.utils.plan_converter

import beam.utils.plan_converter.entities.InputHousehold
import beam.utils.plan_converter.merger.{HouseholdMerger, PersonMerger, PlanMerger}
import beam.utils.plan_converter.reader._
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource}
import org.slf4j.LoggerFactory

class UrbansimReaderV2(
  inputPersonPath: String,
  inputPlanPath: String,
  inputHouseholdPath: String,
  inputTripsPath: String,
  inputBlockPath: String
) extends ScenarioSource {

  private val logger = LoggerFactory.getLogger(getClass)

  private val inputHouseHoldMap: Map[String, InputHousehold] = {
    logger.debug("Start reading of households info...")
    val reader = new HouseHoldReader(inputHouseholdPath)
    try {
      reader
        .iterator()
        .map(h => h.householdId -> h)
        .toMap
    } finally {
      logger.debug("Households info have been read successfully.")
      reader.close()
    }
  }

  override def getPersons: Iterable[PersonInfo] = {
    val merger = new PersonMerger(inputHouseHoldMap)
    val personReader = new PersonReader(inputPersonPath)

    logger.debug("Merging incomes into person...")

    try {
      merger.merge(personReader.iterator()).toList
    } finally {
      logger.debug("Merged successfully.")
      personReader.close()
    }
  }

  override def getPlans: Iterable[PlanElement] = {
    logger.debug("Reading of the trips...")
    val tripReader = new TripReader(inputTripsPath)
    val modes = tripReader
      .iterator()
      .map(tripElement => (tripElement.personId, tripElement.depart) -> tripElement.trip_mode)
      .toMap
    val merger = new PlanMerger(modes)

    logger.debug("Merging modes into plan...")

    val planReader = new PlanReader(inputPlanPath)

    try {
      merger.merge(planReader.iterator()).toList
    } finally {
      logger.debug("Merged successfully.")
      planReader.close()
      tripReader.close()
    }
  }

  override def getHousehold: Iterable[HouseholdInfo] = {
    logger.debug("Reading of the blocks...")
    val blockReader = new BlockReader(inputBlockPath)
    val blocks = blockReader
      .iterator()
      .map(b => b.blockId -> b)
      .toMap
    val merger = new HouseholdMerger(blocks)

    logger.debug("Merging blocks into households...")

    try {
      merger.merge(inputHouseHoldMap.valuesIterator).toList
    } finally {
      logger.debug("Merged successfully.")
      blockReader.close()
    }
  }
}
