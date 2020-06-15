package beam.utils.scenario.urbansim.censusblock

import beam.utils.scenario.urbansim.censusblock.entities.{InputHousehold, TripElement}
import beam.utils.scenario.urbansim.censusblock.merger.{HouseholdMerger, PersonMerger, PlanMerger}
import beam.utils.scenario.urbansim.censusblock.reader._
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
    logger.info("Start reading of households info...")
    val reader = new HouseHoldReader(inputHouseholdPath)
    try {
      reader
        .iterator()
        .map(h => h.householdId -> h)
        .toMap
    } finally {
      logger.info("Households info has been read successfully.")
      reader.close()
    }
  }

  override def getPersons: Iterable[PersonInfo] = {
    val merger = new PersonMerger(inputHouseHoldMap)
    val personReader = new PersonReader(inputPersonPath)

    logger.info("Merging incomes into person...")

    try {
      merger.merge(personReader.iterator()).toList
    } finally {
      logger.info("Incomes merged successfully.")
      personReader.close()
    }
  }

  override def getPlans: Iterable[PlanElement] = {
    logger.info("Reading of the trips...")
    val tripReader = new TripReader(inputTripsPath)
    val modes = tripReader
      .iterator()
      .map(tripElement => (tripElement.personId, tripElement.depart) -> tripElement.trip_mode)
      .toMap
    val merger = new PlanMerger(modes)

    logger.info("Merging modes into plan...")

    val planReader = new PlanReader(inputPlanPath)

    try {
      merger.merge(planReader.iterator()).toList
    } finally {
      logger.info("Modes merged successfully into plan.")
      planReader.close()
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
      logger.debug("Blocks merged successfully.")
      blockReader.close()
    }
  }
}
