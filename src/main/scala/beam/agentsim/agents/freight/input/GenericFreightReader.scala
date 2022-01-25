package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight._
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
import beam.utils.csv.GenericCsvReader
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.conveyal.r5.streets.StreetLayer
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.Household
import beam.agentsim.infrastructure.taz.TAZ

import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class GenericFreightReader(
  val config: Freight,
  val geoUtils: GeoUtils,
  rnd: Random,
  tazTree: TAZTreeMap,
  streetLayerMaybe: Option[StreetLayer]
) extends LazyLogging
    with FreightReader {

  val freightIdPrefix = "freight"

  private def getRowValue(table: String, row: java.util.Map[String, String], key: String): String = {
    if (row.containsKey(key)) {
      row.get(key)
    } else {
      throw new IllegalArgumentException(s"Missing key '$key' in table '$table'.")
    }
  }

  private def findClosestUTMPointOnMap(utmCoord: Coord): Option[Coord] = {
    streetLayerMaybe match {
      case Some(streetLayer) =>
        val wsgCoord = geoUtils.utm2Wgs(utmCoord)
        val theSplit = geoUtils.getR5Split(streetLayer, wsgCoord)
        if (theSplit == null) {
          None
        } else {
          val wgsPointOnMap = geoUtils.splitToCoord(theSplit)
          val utmCoord = geoUtils.wgs2Utm(wgsPointOnMap)
          Some(utmCoord)
        }
      case _ => Some(utmCoord)
    }
  }

  @Override
  def readFreightTours(): Map[Id[FreightTour], FreightTour] = {
    val maybeTours = GenericCsvReader
      .readAsSeq[Option[FreightTour]](config.toursFilePath) { row =>
        def get(key: String): String = getRowValue(config.toursFilePath, row, key)
        // tourId,departureTimeInSec,departureLocationZone,departureLocationX,departureLocationY,maxTourDurationInSec
        val tourId: Id[FreightTour] = get("tourId").createId[FreightTour]
        val departureTimeInSec = get("departureTimeInSec").toInt
        val maxTourDurationInSec = get("maxTourDurationInSec").toInt
        extractCoordOrTaz(
          row.get("departureLocationX"),
          row.get("departureLocationY"),
          row.get("departureLocationZone")
        ) match {
          case (departureLocationZoneMaybe, Some(departureLocationUTMOnMap)) =>
            Some(
              FreightTour(
                tourId,
                departureTimeInSec,
                departureLocationZoneMaybe,
                departureLocationUTMOnMap,
                maxTourDurationInSec
              )
            )
          case _ =>
            logger.error(f"Following freight tour row discarded because departure location is not reachable: $row")
            None
        }
      }

    maybeTours.flatten
      .groupBy(_.tourId)
      .mapValues(_.head)
  }

  @Override
  def readPayloadPlans(): Map[Id[PayloadPlan], PayloadPlan] = {
    val maybePlans = GenericCsvReader
      .readAsSeq[Option[PayloadPlan]](config.plansFilePath) { row =>
        def get(key: String): String = getRowValue(config.plansFilePath, row, key)
        // payloadId,sequenceRank,tourId,payloadType,weightInKg,requestType,locationZone,locationX,locationY,
        // estimatedTimeOfArrivalInSec,arrivalTimeWindowInSecLower,arrivalTimeWindowInSecUpper,operationDurationInSec
        val requestType = get("requestType").toLowerCase() match {
          case "1" | "unloading" => FreightRequestType.Unloading
          case "0" | "loading"   => FreightRequestType.Loading
          case wrongValue =>
            throw new IllegalArgumentException(
              s"Value of requestType $wrongValue is unexpected."
            )
        }
        val operationDurationInSec = get("operationDurationInSec").toDouble.round.toInt
        val activityType = if (config.generateFixedActivitiesDurations) {
          s"${requestType.toString}|$operationDurationInSec"
        } else {
          requestType.toString
        }
        extractCoordOrTaz(row.get("locationX"), row.get("locationY"), row.get("locationZone")) match {
          case (locationZoneMaybe, Some(locationUTMOnMap)) =>
            Some(
              PayloadPlan(
                get("payloadId").createId,
                get("sequenceRank").toDouble.round.toInt,
                get("tourId").createId,
                get("payloadType").createId[PayloadType],
                get("weightInKg").toDouble,
                requestType,
                activityType,
                locationZoneMaybe,
                locationUTMOnMap,
                get("estimatedTimeOfArrivalInSec").toDouble.toInt,
                get("arrivalTimeWindowInSecLower").toDouble.toInt,
                get("arrivalTimeWindowInSecUpper").toDouble.toInt,
                operationDurationInSec
              )
            )
          case _ =>
            logger.error(f"Following freight plan row discarded because zone location is not reachable: $row")
            None
        }
      }

    maybePlans.flatten
      .groupBy(_.payloadId)
      .mapValues(_.head)
  }

  @Override
  def readFreightCarriers(
    allTours: Map[Id[FreightTour], FreightTour],
    allPlans: Map[Id[PayloadPlan], PayloadPlan],
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]
  ): IndexedSeq[FreightCarrier] = {

    val existingTours: Set[Id[FreightTour]] = allTours.keySet.intersect(allPlans.map(_._2.tourId).toSet)
    val plans: Map[Id[PayloadPlan], PayloadPlan] = allPlans.filter { case (_, plan) =>
      existingTours.contains(plan.tourId)
    }
    val tours: Map[Id[FreightTour], FreightTour] = allTours.filter { case (_, tour) =>
      existingTours.contains(tour.tourId)
    }

    case class FreightCarrierRow(
      carrierId: Id[FreightCarrier],
      tourId: Id[FreightTour],
      vehicleId: Id[BeamVehicle],
      vehicleTypeId: Id[BeamVehicleType],
      warehouseLocationZone: Option[Id[TAZ]],
      warehouseLocationUTM: Coord
    )

    def createCarrierVehicles(
      carrierId: Id[FreightCarrier],
      carrierRows: IndexedSeq[FreightCarrierRow],
      warehouseLocationUTM: Coord
    ): IndexedSeq[BeamVehicle] = {
      val vehicles: IndexedSeq[BeamVehicle] = carrierRows
        .groupBy(_.vehicleId)
        .map { case (vehicleId, rows) =>
          val firstRow = rows.head
          val vehicleType = vehicleTypes.getOrElse(
            firstRow.vehicleTypeId,
            throw new IllegalArgumentException(
              s"Vehicle type for vehicle $vehicleId not found: ${firstRow.vehicleTypeId}"
            )
          )
          if (vehicleType.payloadCapacityInKg.isEmpty)
            throw new IllegalArgumentException(
              s"Vehicle type ${firstRow.vehicleTypeId} for vehicle $vehicleId has no payloadCapacityInKg defined"
            )
          createFreightVehicle(vehicleId, vehicleType, carrierId, warehouseLocationUTM, rnd.nextInt())
        }
        .toIndexedSeq
      vehicles
    }

    def createCarrier(carrierId: Id[FreightCarrier], carrierRows: IndexedSeq[FreightCarrierRow]) = {
      val warehouseLocationUTM: Coord = carrierRows.head.warehouseLocationUTM
      val vehicles: scala.IndexedSeq[BeamVehicle] = createCarrierVehicles(carrierId, carrierRows, warehouseLocationUTM)
      val vehicleMap: Map[Id[BeamVehicle], BeamVehicle] = vehicles.map(vehicle => vehicle.id -> vehicle).toMap

      val tourMap: Map[Id[BeamVehicle], IndexedSeq[FreightTour]] = carrierRows
        .groupBy(_.vehicleId)
        .mapValues { rows =>
          rows
            //setting the tour warehouse location to be the carrier warehouse location
            .map(row => tours(row.tourId).copy(warehouseLocationUTM = warehouseLocationUTM))
            .sortBy(_.departureTimeInSec)
        }

      val carrierTourIds = tourMap.values.flatten.map(_.tourId).toSet

      val plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]] =
        plans.values.groupBy(_.tourId).filterKeys(carrierTourIds).mapValues(_.toIndexedSeq.sortBy(_.sequenceRank))
      val carrierPlanIds: Set[Id[PayloadPlan]] = plansPerTour.values.flatten.map(_.payloadId).toSet
      val payloadMap = plans.filterKeys(carrierPlanIds)

      FreightCarrier(carrierId, tourMap, payloadMap, vehicleMap, plansPerTour)
    }

    val maybeCarrierRows = GenericCsvReader.readAsSeq[Option[FreightCarrierRow]](config.carriersFilePath) { row =>
      def get(key: String): String = getRowValue(config.carriersFilePath, row, key)
      //carrierId,tourId,vehicleId,vehicleTypeId,warehouseZone,warehouseX,warehouseY
      val carrierId: Id[FreightCarrier] = s"$freightIdPrefix-carrier-${get("carrierId")}".createId
      val tourId: Id[FreightTour] = get("tourId").createId
      val vehicleId: Id[BeamVehicle] = Id.createVehicleId(s"$freightIdPrefix-vehicle-${get("vehicleId")}")
      val vehicleTypeId: Id[BeamVehicleType] = get("vehicleTypeId").createId
      if (!existingTours.contains(tourId)) {
        logger.error(f"Following freight carrier row discarded because tour $tourId was filtered out: $row")
        None
      } else {
        extractCoordOrTaz(row.get("warehouseX"), row.get("warehouseY"), row.get("warehouseZone")) match {
          case (warehouseZoneMaybe, Some(warehouseUTMOnMap)) =>
            Some(FreightCarrierRow(carrierId, tourId, vehicleId, vehicleTypeId, warehouseZoneMaybe, warehouseUTMOnMap))
          case (_, warehouseXYMaybe) =>
            logger.error(
              f"Following freight carrier row discarded because warehouse location ($warehouseXYMaybe) is not reachable: $row"
            )
            None
        }
      }
    }

    val carriersWithFleet = maybeCarrierRows.flatten
      .groupBy(_.carrierId)
      .map { case (carrierId, carrierRows) =>
        createCarrier(carrierId, carrierRows)
      }
      .toIndexedSeq

    carriersWithFleet
  }

  private def getTaz(tazId: String): TAZ = tazTree.getTAZ(tazId) match {
    case Some(taz) => taz
    case None      => throw new IllegalArgumentException(s"Cannot find taz with id $tazId")
  }

  private def getDistributedTazLocation(taz: TAZ): Coord =
    convertedLocation(TAZTreeMap.randomLocationInTAZ(taz, rnd))

  private def checkNullOrEmpty(str: String): Boolean = str == null || str.isEmpty

  private def extractCoordOrTaz(strX: String, strY: String, strZone: String): (Option[Id[TAZ]], Option[Coord]) = {
    if (checkNullOrEmpty(strX) || checkNullOrEmpty(strY)) {
      val taz = getTaz(strZone)
      (Some(taz.tazId), Some(getDistributedTazLocation(taz)))
    } else {
      val loc = location(strX.toDouble, strY.toDouble)
      val locOnMap = findClosestUTMPointOnMap(loc) orElse {
        logger.error(
          f"Following freight tour row discarded because departure location is not reachable: X=$strX,Y=$strY"
        )
        None
      }
      (None, locOnMap)
    }
  }

  @Override
  def createPersonId(vehicleId: Id[BeamVehicle]): Id[Person] = {
    if (vehicleId.toString.startsWith(freightIdPrefix)) {
      Id.createPersonId(s"$vehicleId-agent")
    } else {
      Id.createPersonId(s"$freightIdPrefix-$vehicleId-agent")
    }
  }

  @Override
  def createHouseholdId(vehicleId: Id[BeamVehicle]): Id[Household] = {
    if (vehicleId.toString.startsWith(freightIdPrefix)) {
      s"$vehicleId-household".createId
    } else {
      s"$freightIdPrefix-$vehicleId-household".createId
    }
  }

}
