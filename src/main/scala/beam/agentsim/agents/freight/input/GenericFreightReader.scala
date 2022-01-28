package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight._
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.taz.TAZTreeMap
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
import beam.utils.csv.GenericCsvReader
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.households.Household

import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class GenericFreightReader(val config: Freight, val geoUtils: GeoUtils, rnd: Random, tazTree: TAZTreeMap)
    extends LazyLogging
    with FreightReader {

  private def getDistributedTazLocation(tazId: String): Coord =
    tazTree.getTAZ(tazId) match {
      case Some(taz) => convertedLocation(TAZTreeMap.randomLocationInTAZ(taz, rnd))
      case None      => throw new IllegalArgumentException(s"Cannot find taz with id $tazId")
    }

  @Override
  def readFreightTours(): Map[Id[FreightTour], FreightTour] = {
    GenericCsvReader
      .readAsSeq[FreightTour](config.toursFilePath) { row =>
        //tourId,departureTimeInSec,departureLocationX,departureLocationY,maxTourDurationInSec
        val tourId: Id[FreightTour] = row.get("tourId").createId[FreightTour]
        val departureTimeInSec = row.get("departureTimeInSec").toInt
        val departureLocationX = row.get("departureLocationX").toDouble
        val departureLocationY = row.get("departureLocationY").toDouble
        val maxTourDurationInSec = row.get("maxTourDurationInSec").toInt
        FreightTour(
          tourId,
          departureTimeInSec,
          location(departureLocationX, departureLocationY),
          maxTourDurationInSec
        )
      }
      .groupBy(_.tourId)
      .mapValues(_.head)
  }

  @Override
  def readPayloadPlans(): Map[Id[PayloadPlan], PayloadPlan] = {
    GenericCsvReader
      .readAsSeq[PayloadPlan](config.plansFilePath) { row =>
        //payloadId,sequenceRank,tourId,payloadType,weightInKg,requestType,locationX,locationY,estimatedTimeOfArrivalInSec,arrivalTimeWindowInSec,operationDurationInSec
        val requestType = FreightRequestType.withNameInsensitive(row.get("requestType"))
        PayloadPlan(
          row.get("payloadId").createId,
          row.get("sequenceRank").toInt,
          row.get("tourId").createId,
          row.get("payloadType").createId[PayloadType],
          row.get("weightInKg").toDouble,
          requestType,
          activityType = requestType.toString,
          getDistributedTazLocation(row.get("taz")),
          row.get("estimatedTimeOfArrivalInSec").toInt,
          row.get("arrivalTimeWindowInSec").toInt,
          row.get("operationDurationInSec").toInt
        )
      }
      .groupBy(_.payloadId)
      .mapValues(_.head)
  }

  @Override
  def readFreightCarriers(
    allTours: Map[Id[FreightTour], FreightTour],
    allPlans: Map[Id[PayloadPlan], PayloadPlan],
    vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType]
  ): IndexedSeq[FreightCarrier] = {

    case class FreightCarrierRow(
      carrierId: Id[FreightCarrier],
      tourId: Id[FreightTour],
      vehicleId: Id[BeamVehicle],
      vehicleTypeId: Id[BeamVehicleType],
      warehouseTaz: String
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
      val warehouseLocationUTM: Coord = getDistributedTazLocation(carrierRows.head.warehouseTaz)
      val vehicles: scala.IndexedSeq[BeamVehicle] = createCarrierVehicles(carrierId, carrierRows, warehouseLocationUTM)
      val vehicleMap: Map[Id[BeamVehicle], BeamVehicle] = vehicles.map(vehicle => vehicle.id -> vehicle).toMap

      val tourMap: Map[Id[BeamVehicle], IndexedSeq[FreightTour]] = carrierRows
        .groupBy(_.vehicleId)
        .mapValues { rows =>
          rows
            //setting the tour warehouse location to be the carrier warehouse location
            .map(row => allTours(row.tourId).copy(warehouseLocationUTM = warehouseLocationUTM))
            .sortBy(_.departureTimeInSec)
        }

      val carrierTourIds = tourMap.values.flatten.map(_.tourId).toSet

      val plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]] =
        allPlans.values.groupBy(_.tourId).filterKeys(carrierTourIds).mapValues(_.toIndexedSeq.sortBy(_.sequenceRank))
      val carrierPlanIds: Set[Id[PayloadPlan]] = plansPerTour.values.flatten.map(_.payloadId).toSet
      val payloadMap = allPlans.filterKeys(carrierPlanIds)

      FreightCarrier(carrierId, tourMap, payloadMap, vehicleMap, plansPerTour)
    }

    val rows = GenericCsvReader.readAsSeq[FreightCarrierRow](config.carriersFilePath) { row =>
      //carrierId,tourId,vehicleId,vehicleTypeId,warehouseTAZ
      val carrierId: Id[FreightCarrier] = row.get("carrierId").createId
      val tourId: Id[FreightTour] = row.get("tourId").createId
      val vehicleId: Id[BeamVehicle] = Id.createVehicleId(row.get("vehicleId"))
      val vehicleTypeId: Id[BeamVehicleType] = row.get("vehicleTypeId").createId
      val warehouseTaz = row.get("warehouseTAZ")
      FreightCarrierRow(carrierId, tourId, vehicleId, vehicleTypeId, warehouseTaz)
    }
    rows
      .groupBy(_.carrierId)
      .map { case (carrierId, carrierRows) =>
        createCarrier(carrierId, carrierRows)
      }
      .toIndexedSeq
  }

  @Override
  def createPersonId(vehicleId: Id[BeamVehicle]): Id[Person] = Id.createPersonId(s"freight-agent-$vehicleId")

  @Override
  def createHouseholdId(vehicleId: Id[BeamVehicle]): Id[Household] = s"freight-household-$vehicleId".createId

}
