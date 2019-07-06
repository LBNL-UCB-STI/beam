package beam.utils.scenario.urbansim

import beam.sim.common.GeoUtils
import beam.utils.ProfilingUtils
import beam.utils.scenario._
import beam.utils.scenario.urbansim.DataExchange.{
  BuildingInfo,
  ParcelAttribute,
  UnitInfo,
  HouseholdInfo => UrbanHouseholdInfo
}
import org.matsim.api.core.v01.Coord
import scala.collection.parallel.immutable.ParMap

import beam.utils.logging.ExponentialLazyLogging

class UrbanSimScenarioSource(
  val scenarioFolder: String,
  val rdr: UrbanSimScenarioReader,
  val geoUtils: GeoUtils,
  val shouldConvertWgs2Utm: Boolean
) extends ScenarioSource
    with ExponentialLazyLogging {
  val fileExt: String = rdr.inputType.toFileExt

  val buildingFilePath: String = s"$scenarioFolder/buildings.$fileExt"
  val personFilePath: String = s"$scenarioFolder/persons.$fileExt"
  val householdFilePath: String = s"$scenarioFolder/households.$fileExt"
  val planFilePath: String = s"$scenarioFolder/plans.$fileExt"
  val unitFilePath: String = s"$scenarioFolder/units.$fileExt"
  val parcelAttrFilePath: String = s"$scenarioFolder/parcels.$fileExt"

  override def getPersons: Iterable[PersonInfo] = {
    rdr.readPersonsFile(personFilePath).map { person: DataExchange.PersonInfo =>
      PersonInfo(
        personId = PersonId(person.personId),
        householdId = HouseholdId(person.householdId),
        rank = person.rank,
        age = person.age,
        isFemale = person.isFemale,
        valueOfTime = person.valueOfTime
      )
    }
  }
  override def getPlans: Iterable[PlanElement] = {
    val rawPlanElements: Array[DataExchange.PlanElement] = rdr.readPlansFile(planFilePath)
    val planElements: Array[DataExchange.PlanElement] = dropCorruptedPlanElements(rawPlanElements)
    if (rawPlanElements.length != planElements.length) {
      logger.error(
        s"$planFilePath contains ${rawPlanElements.length} planElement, after removing corrupted data: ${planElements.length}"
      )
    }

    planElements.map { plan =>
      val coord = (plan.x, plan.y) match {
        case (Some(x), Some(y)) =>
          val c =
            if (shouldConvertWgs2Utm)
              geoUtils.wgs2Utm(new Coord(x, y))
            else
              new Coord(x, y)
          Some(c)
        case (Some(x), None) =>
          logger.warn(s"Plan with PersonId[${plan.personId}] has X coordinate [$x], but has not Y")
          None
        case (None, Some(y)) =>
          logger.warn(s"Plan with PersonId[${plan.personId}] has Y coordinate [$y], but has not X")
          None
        case _ =>
          None
      }
      PlanElement(
        personId = PersonId(plan.personId),
        planElementType = plan.planElement,
        planElementIndex = plan.planElementIndex,
        activityType = plan.activityType,
        activityLocationX = coord.map(_.getX),
        activityLocationY = coord.map(_.getY),
        activityEndTime = plan.endTime,
        legMode = plan.mode
      )
    }
  }
  override def getHousehold: Iterable[HouseholdInfo] = {
    val householdInfo = rdr.readHouseholdsFile(householdFilePath)
    val householdIdToCoord = getHouseholdIdToCoord(householdInfo)
    householdInfo.map { householdInfo =>
      val coord = householdIdToCoord.getOrElse(householdInfo.householdId, {
        logger.warn(s"Could not find coordinate for `householdId` '{}'", householdInfo.householdId)
        new Coord(0, 0)
      })
      HouseholdInfo(
        householdId = HouseholdId(householdInfo.householdId),
        cars = householdInfo.cars,
        income = householdInfo.income,
        locationX = coord.getX,
        locationY = coord.getY
      )
    }
  }

  private def getHouseholdIdToCoord(householdsWithMembers: Array[UrbanHouseholdInfo]): Map[String, Coord] = {
    val units = rdr.readUnitsFile(unitFilePath)
    val parcelAttrs = rdr.readParcelAttrFile(parcelAttrFilePath)
    val buildings = rdr.readBuildingsFile(buildingFilePath)
    val unitIdToCoord = ProfilingUtils.timed("getUnitIdToCoord", x => logger.info(x)) {
      getUnitIdToCoord(units, parcelAttrs, buildings)
    }
    householdsWithMembers.map { hh =>
      // Coordinates already converted, so we should not use `wgs2Utm` again
      val coord = unitIdToCoord.getOrElse(hh.unitId, {
        logger.warn(s"Could not find coordinate for `household` ${hh.householdId} and `unitId`'${hh.unitId}'")
        new Coord(0, 0)
      })
      hh.householdId -> coord
    }.toMap
  }

  private[urbansim] def getUnitIdToCoord(
    units: Array[UnitInfo],
    parcelAttrs: Array[ParcelAttribute],
    buildings: Array[BuildingInfo]
  ): Map[String, Coord] = {
    val parcelIdToCoord: ParMap[String, Coord] = parcelAttrs.par.groupBy(_.primaryId).map {
      case (k, v) =>
        val pa = v.head
        val coord = if (shouldConvertWgs2Utm) {
          geoUtils.wgs2Utm(new Coord(pa.x, pa.y))
        } else {
          new Coord(pa.x, pa.y)
        }
        k -> coord
    }
    val buildingId2ToParcelId: ParMap[String, String] =
      buildings.par.groupBy(x => x.buildingId).map { case (k, v) => k -> v.head.parcelId }
    val unitIdToBuildingId: ParMap[String, String] =
      units.par.groupBy(_.unitId).map { case (k, v) => k -> v.head.buildingId }

    unitIdToBuildingId.map {
      case (unitId, buildingId) =>
        val coord = buildingId2ToParcelId.get(buildingId) match {
          case Some(parcelId) =>
            parcelIdToCoord.getOrElse(parcelId, {
              logger.warn(s"Could not find coordinate for `parcelId` '$parcelId'")
              new Coord(0, 0)
            })
          case None =>
            logger.warn(s"Could not find `parcelId` for `building_id` '$buildingId'")
            new Coord(0, 0)
        }
        unitId -> coord
    }.seq
  }

  private def dropCorruptedPlanElements(rawPlans: Array[DataExchange.PlanElement]): Array[DataExchange.PlanElement] = {
    val correctPlanElements = rawPlans
      .groupBy(x => x.personId)
      .filter {
        case (k, v) =>
          val isCorrupted = v.exists(x => x.planElementIndex == 1 && x.endTime.isEmpty)
          !isCorrupted
      }
      .flatMap { case (k, v) => v.sortBy(x => x.planElementIndex) }
      .toArray
    correctPlanElements
  }
}
