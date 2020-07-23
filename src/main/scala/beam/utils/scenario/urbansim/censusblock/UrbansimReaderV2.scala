package beam.utils.scenario.urbansim.censusblock

import beam.sim.common.GeoUtils
import beam.utils.scenario.urbansim.censusblock.entities.{InputHousehold, TripElement}
import beam.utils.scenario.urbansim.censusblock.merger.{HouseholdMerger, PersonMerger, PlanMerger}
import beam.utils.scenario.urbansim.censusblock.reader._
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource}
import org.matsim.api.core.v01.Coord
import org.slf4j.LoggerFactory

class UrbansimReaderV2(
  val inputPersonPath: String,
  val inputPlanPath: String,
  val inputHouseholdPath: String,
  val inputTripsPath: String,
  val inputBlockPath: String,
  val geoUtils: GeoUtils,
  val shouldConvertWgs2Utm: Boolean
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
      merger
        .merge(planReader.iterator())
        .map { plan: PlanElement =>
          if (plan.planElementType.equalsIgnoreCase("activity") && shouldConvertWgs2Utm) {
            val utmCoord = geoUtils.wgs2Utm(new Coord(plan.activityLocationX.get, plan.activityLocationY.get))
            plan.copy(activityLocationX = Some(utmCoord.getX), activityLocationY = Some(utmCoord.getY))
          } else {
            plan
          }
        }
        .toList
    } finally {
      logger.info("Modes merged successfully into plan.")
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
      merger
        .merge(inputHouseHoldMap.valuesIterator)
        .map { household =>
          if (shouldConvertWgs2Utm) {
            val utmCoord = geoUtils.wgs2Utm(new Coord(household.locationX, household.locationY))
            household.copy(locationX = utmCoord.getX, locationY = utmCoord.getY)
          } else {
            household
          }
        }
        .toList
    } finally {
      logger.debug("Blocks merged successfully.")
      blockReader.close()
    }
  }
}
