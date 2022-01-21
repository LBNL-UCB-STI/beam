package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight._
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
import beam.utils.csv.GenericCsvReader
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.conveyal.r5.streets.StreetLayer
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.Household

import scala.util.Random

class NRELFreightReader(
  val config: Freight,
  val geoUtils: GeoUtils,
  rnd: Random,
  streetLayer: StreetLayer,
  val beamConfig: BeamConfig
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
    val wsgCoord = geoUtils.utm2Wgs(utmCoord)
    val theSplit = geoUtils.getR5Split(streetLayer, wsgCoord, beamConfig.beam.routing.r5.linkRadiusMeters)
    if (theSplit == null) {
      None
    } else {
      val wgsPointOnMap = geoUtils.splitToCoord(theSplit)
      val utmCoord = geoUtils.wgs2Utm(wgsPointOnMap)
      Some(utmCoord)
    }
  }

  @Override
  def readFreightTours(): Map[Id[FreightTour], FreightTour] = {
    val maybeTours = GenericCsvReader
      .readAsSeq[Option[FreightTour]](config.toursFilePath) { row =>
        def get(key: String): String = getRowValue(config.toursFilePath, row, key)
        // tourId,departureTimeInSec,departureLocation_zone,maxTourDurationInSec,departureLocationX,departureLocationY
        val tourId: Id[FreightTour] = get("tour_id").createId[FreightTour]
        val departureTimeInSec = get("departureTimeInSec").toDouble.round.toInt
        val maxTourDurationInSec = get("maxTourDurationInSec").toDouble.round.toInt
        val departureLocationUTM = location(get("departureLocation_x").toDouble, get("departureLocation_y").toDouble)

        findClosestUTMPointOnMap(departureLocationUTM) match {
          case Some(departureLocationUTMOnMap) =>
            Some(
              FreightTour(
                tourId,
                departureTimeInSec,
                departureLocationUTMOnMap,
                maxTourDurationInSec
              )
            )
          case None =>
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
        // payloadId,sequenceRank,tourId,payloadType,weightInlb,requestType,locationZone,
        // estimatedTimeOfArrivalInSec,arrivalTimeWindowInSec_lower,arrivalTimeWindowInSec_upper,
        // operationDurationInSec,locationZone_x,locationZone_y
        val weightInKg =
          if (row.containsKey("weightInKg")) get("weightInKg").toDouble
          else get("weightInlb").toDouble / 2.20462
        val locationUTM = location(get("locationZone_x").toDouble, get("locationZone_y").toDouble)

        val arrivalTimeWindowInSec: Int = {
          val lower = get("arrivalTimeWindowInSec_lower").toDouble
          val upper = get("arrivalTimeWindowInSec_upper").toDouble
          Math.min(lower, upper) + Math.abs(lower - upper) / 2
        }.round.toInt
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

        findClosestUTMPointOnMap(locationUTM) match {
          case Some(locationUTMOnMap) =>
            Some(
              PayloadPlan(
                get("payloadId").createId,
                get("sequenceRank").toDouble.round.toInt,
                get("tourId").createId,
                get("payloadType").createId[PayloadType],
                weightInKg,
                requestType,
                activityType,
                locationUTMOnMap,
                get("estimatedTimeOfArrivalInSec").toDouble.toInt,
                arrivalTimeWindowInSec,
                operationDurationInSec
              )
            )
          case None =>
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
      // carrierId,tourId,vehicleId,vehicleTypeId,depot_zone,depot_zone_x,depot_zone_y
      val carrierId: Id[FreightCarrier] = s"$freightIdPrefix-carrier-${get("carrierId")}".createId
      val tourId: Id[FreightTour] = get("tourId").createId
      val vehicleId: Id[BeamVehicle] = Id.createVehicleId(s"$freightIdPrefix-vehicle-${get("vehicleId")}")
      val vehicleTypeId: Id[BeamVehicleType] = get("vehicleTypeId").createId
      val warehouseLocationUTM = location(get("depot_zone_x").toDouble, get("depot_zone_y").toDouble)

      if (!existingTours.contains(tourId)) {
        logger.error(f"Following freight carrier row discarded because tour $tourId was filtered out: $row")
        None
      } else {
        findClosestUTMPointOnMap(warehouseLocationUTM) match {
          case Some(warehouseLocationUTMOnMap) =>
            Some(FreightCarrierRow(carrierId, tourId, vehicleId, vehicleTypeId, warehouseLocationUTMOnMap))
          case None =>
            logger.error(
              f"Following freight carrier row discarded because warehouse location ($warehouseLocationUTM) is not reachable: $row"
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
