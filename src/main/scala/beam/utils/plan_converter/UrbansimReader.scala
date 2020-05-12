package beam.utils.plan_converter

import java.io.{BufferedWriter, FileWriter}

import beam.utils.FileUtils
import beam.utils.plan_converter.UrbansimConverter.{getTripModes, logger, merge, readPlan, writePlans}
import beam.utils.plan_converter.entities.TripElement
import beam.utils.plan_converter.merger.PlanMerger
import beam.utils.plan_converter.reader.{PlanReader, Reader, TripReader}
import beam.utils.scenario.InputType
import beam.utils.scenario.urbansim.{DataExchange, UrbanSimScenarioReader}
import org.slf4j.LoggerFactory

class UrbansimReader(inputTripsPath: String) extends UrbanSimScenarioReader{

  private val logger = LoggerFactory.getLogger(getClass)

  override def inputType: InputType = InputType.CSV

  override def readUnitsFile(path: String): Array[DataExchange.UnitInfo] = Array.empty

  override def readParcelAttrFile(path: String): Array[DataExchange.ParcelAttribute] = Array.empty

  override def readBuildingsFile(path: String): Array[DataExchange.BuildingInfo] = Array.empty

  override def readPersonsFile(path: String): Array[DataExchange.PersonInfo] = ???

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
