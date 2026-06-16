package beam.utils.scenario.urbansim.censusblock

import beam.sim.common.GeoUtils
import beam.utils.csv.readers
import beam.utils.scenario.urbansim.censusblock.entities.{Block, InputHousehold, InputPersonInfo, InputPlanElement}
import beam.utils.scenario.urbansim.censusblock.merger.{HouseholdMerger, PersonMerger, PlanMerger}
import beam.utils.scenario.urbansim.censusblock.reader._
import beam.utils.scenario.{HouseholdInfo, PersonInfo, PlanElement, ScenarioSource, VehicleInfo}
import beam.utils.BeamVehicleUtils
import org.matsim.api.core.v01.Coord
import com.typesafe.scalalogging.LazyLogging
import beam.utils.scenario.urbansim.censusblock.reader.ReaderFactories._

import java.nio.file.{Files, Paths}

class UrbansimReaderV2(
  val inputPersonPath: String,
  val inputPlanPath: String,
  val inputHouseholdPath: String,
  val inputVehiclePath: String,
  val inputBlockPath: String,
  val geoUtils: GeoUtils,
  val shouldConvertWgs2Utm: Boolean,
  val modeMap: Map[String, String],
  val fileFormat: String = "csv"
) extends ScenarioSource
    with LazyLogging {

  private val rdr = readers.BeamCsvScenarioReader

  if (fileFormat == "parquet") {
    val requiredFiles = List(
      inputPersonPath,
      inputPlanPath,
      inputHouseholdPath,
      inputBlockPath
    )
    val missingFiles = requiredFiles.filterNot(path => Files.exists(Paths.get(path)))
    require(
      missingFiles.isEmpty,
      s"All Parquet files must exist. Missing files: ${missingFiles.mkString(", ")}"
    )
  }

  private val inputHouseHoldMap: Map[String, InputHousehold] = {
    logger.info("Start reading of households info...")
    val reader = createReader[InputHousehold](inputHouseholdPath, fileFormat)
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
    val personReader = createReader[InputPersonInfo](inputPersonPath, fileFormat)

    logger.info("Merging incomes into person...")

    try {
      merger.merge(personReader.iterator()).toList
    } finally {
      logger.info("Incomes merged successfully.")
      personReader.close()
    }
  }

  override def getPlans: Iterable[PlanElement] = {
    val merger = new PlanMerger(modeMap)

    logger.info("Merging modes into plan...")

    val planReader = createReader[InputPlanElement](inputPlanPath, fileFormat)

    try {
      merger
        .merge(planReader.iterator())
        .map { plan: PlanElement =>
          if (plan.planElementType == PlanElement.Activity && shouldConvertWgs2Utm) {
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
    }
  }

  override def getHousehold: Iterable[HouseholdInfo] = {
    logger.debug("Reading of the blocks...")
    val blockReader = createReader[Block](inputBlockPath, fileFormat)

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

  override lazy val getVehicles: Iterable[VehicleInfo] = {
    if (Files.exists(Paths.get(inputVehiclePath))) {
      BeamVehicleUtils.readVehicleInfosFile(inputVehiclePath)
    } else {
      Iterable.empty[VehicleInfo]
    }
  }

  private def createReader[T](path: String, format: String)(implicit factory: ReaderFactory[T]): Reader[T] = {
    factory.createReader(path, format)
  }
}
