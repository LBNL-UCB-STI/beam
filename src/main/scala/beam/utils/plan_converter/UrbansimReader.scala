package beam.utils.plan_converter

import beam.utils.plan_converter.entities.{InputHousehold, TripElement}
import beam.utils.plan_converter.merger.{PersonMerger, PlanMerger}
import beam.utils.plan_converter.reader.{HouseHoldReader, PersonReader, PlanReader, Reader, TripReader}
import beam.utils.scenario.InputType
import beam.utils.scenario.urbansim.{DataExchange, UrbanSimScenarioReader}
import org.slf4j.LoggerFactory

class UrbansimReader(inputTripsPath: String, inputHouseholdPath: String) extends UrbanSimScenarioReader{

  private val logger = LoggerFactory.getLogger(getClass)

  private val inputHouseholds: Array[InputHousehold] = {
    val reader = new HouseHoldReader(inputHouseholdPath)
    try {
      reader.iterator().toArray
    } finally {
      reader.close()
    }
  }

  override def inputType: InputType = InputType.CSV

  override def readUnitsFile(path: String): Array[DataExchange.UnitInfo] = Array.empty

  override def readParcelAttrFile(path: String): Array[DataExchange.ParcelAttribute] = Array.empty

  override def readBuildingsFile(path: String): Array[DataExchange.BuildingInfo] = Array.empty

  override def readPersonsFile(path: String): Array[DataExchange.PersonInfo] = {
    val inputHouseHoldMap: Map[Int, InputHousehold] = inputHouseholds.groupBy(_.householdId)
    val merger = new PersonMerger(inputHouseHoldMap)
    val personReader = new PersonReader(path)

    try {
      merger.merge(personReader.iterator()).toArray
    } finally {
      personReader.close()
    }
  }

  override def readPlansFile(path: String): Array[DataExchange.PlanElement] = {
    logger.debug("Reading of the trips...")
    val tripReader = new TripReader(inputTripsPath)
    val modes = getTripModes(tripReader)
    val merger = new PlanMerger(modes)

    logger.debug("Merging modes into plan...")

    val planReader = new PlanReader(path)

    try {
      merger.merge(planReader.iterator()).toArray
    } finally {
      planReader.close()
      tripReader.close()
    }
  }

  override def readHouseholdsFile(path: String): Array[DataExchange.HouseholdInfo] = ???

  private def getTripModes(reader: Reader[TripElement]): Map[(Int, Double), String] =
    reader
      .iterator()
      .map(tripElement => (tripElement.personId, tripElement.depart) -> tripElement.trip_mode)
      .toMap
}
