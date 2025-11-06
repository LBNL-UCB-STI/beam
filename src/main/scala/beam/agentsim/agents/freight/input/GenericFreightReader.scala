package beam.agentsim.agents.freight.input

import beam.agentsim.agents.freight.FreightEntities.FREIGHT_ID_PREFIX
import beam.agentsim.agents.freight._
import beam.agentsim.agents.freight.input.FreightReader._
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import beam.agentsim.infrastructure.taz.{TAZ, TAZTreeMap}
import beam.sim.common.GeoUtils
import beam.sim.config.BeamConfig.Beam.Agentsim.Agents.Freight
import beam.utils.BeamVehicleUtils.readBeamVehicleTypeFile
import beam.utils.SnapCoordinateUtils
import beam.utils.SnapCoordinateUtils._
import beam.utils.csv.GenericCsvReader
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.lang3.StringUtils.isBlank
import org.matsim.api.core.v01.network.Network
import org.matsim.api.core.v01.population._
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.network.NetworkUtils
import org.matsim.households.Household

import scala.collection.mutable.ListBuffer
import scala.util.Random

/**
  * @author Dmitry Openkov
  */
class GenericFreightReader(
  val config: Freight,
  val geoUtils: GeoUtils,
  rnd: Random,
  tazTree: TAZTreeMap,
  val snapLocationAndRemoveInvalidInputs: Boolean,
  schedulerParallelismWindow: Int,
  val snapLocationHelper: SnapLocationHelper,
  networkMaybe: Option[Network] = None,
  val outputDirMaybe: Option[String] = None
) extends LazyLogging
    with FreightReader {

  private def getRowValue(table: String, row: java.util.Map[String, String], key: String): String = {
    if (row.containsKey(key)) {
      row.get(key)
    } else {
      throw new IllegalArgumentException(s"Missing key '$key' in table '$table'.")
    }
  }

  @Override
  def readFreightTours(): Map[Id[FreightTour], FreightTour] = {
    val errors: ListBuffer[ErrorInfo] = ListBuffer()

    val maybeTours = GenericCsvReader
      .readAsSeq[Option[FreightTour]](config.toursFilePath) { row =>
        def get(key: String): String = getRowValue(config.toursFilePath, row, key)
        // tourId,departureTimeInSec,departureLocationZone,departureLocationX,departureLocationY,maxTourDurationInSec
        val tourId: Id[FreightTour] = get("tourId").createId[FreightTour]
        val departureTimeInSec = Math.max(get("departureTimeInSec").toInt, schedulerParallelismWindow + 1)
        val maxTourDurationInSec = get("maxTourDurationInSec").toInt
        val departureLocationX = row.get("departureLocationX")
        val departureLocationY = row.get("departureLocationY")

        extractProjectedCoordOrTaz(
          departureLocationX,
          departureLocationY,
          row.get("departureLocationZone"),
          snapLocationAndRemoveInvalidInputs
        ) match {
          case (_, Right(_)) =>
            Some(
              FreightTour(
                tourId,
                departureTimeInSec,
                maxTourDurationInSec
              )
            )
          case (_, Left(error)) =>
            errors.append(
              ErrorInfo(
                tourId.toString,
                Category.FreightTour,
                error,
                departureLocationX.toDouble,
                departureLocationY.toDouble
              )
            )
            None
        }
      }

    outputDirMaybe.foreach { path =>
      if (errors.isEmpty) logger.info("No 'snap location' error to report for freight tours.")
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.FreightTours}", errors)
    }

    maybeTours.flatten
      .groupBy(_.tourId)
      .mapValues(_.head)
  }

  @Override
  def readPayloadPlans(): Map[Id[PayloadPlan], PayloadPlan] = {
    /*
      a- PayloadType represents the commodity type: 1: bulk, 2: fuel_fert, 3: interm_food, 4: mfr_goods, 5: others
      b- RequestType represent delivery type: 1: delivery only (private truck); 3 pickup-delivery (for-hire truck)
      c- Weighlnlb: + means loading while – means unloading
      d- cummulativeWeightInlb: total loaded weight in truck from the stop i to i+1
      e- true_location: original zones before assigning external zones to a zone along the border of study area.
      f- BuyerNAICS/SellerNAIC: This is based on the customers, so “NA” means that it is a depot (no info)
      g- Truck mode: This is based on the customers, so “NA” means that it is a depot (no info)
     */
    // TODO: Incorporate all changes
    val errors: ListBuffer[ErrorInfo] = ListBuffer()

    val maybePlans = GenericCsvReader
      .readAsSeq[Option[PayloadPlan]](config.plansFilePath) { row =>
        def get(key: String): String = getRowValue(config.plansFilePath, row, key)
        // payloadId,sequenceRank,tourId,payloadType,weightInKg,activityType,locationZone,locationX,locationY,
        // estimatedTimeOfArrivalInSec,arrivalTimeWindowInSecLower,arrivalTimeWindowInSecUpper,operationDurationInSec
        val unloadingStr = FreightActivityType.Unloading.toString.toLowerCase
        val loadingStr = FreightActivityType.Loading.toString.toLowerCase
        val depotStr = FreightActivityType.Depot.toString.toLowerCase
        val activityType = get("activityType").toLowerCase() match {
          case "1" | `unloadingStr` => FreightActivityType.Unloading
          case "0" | `loadingStr`   => FreightActivityType.Loading
          case `depotStr`           => FreightActivityType.Depot
          case wrongValue =>
            throw new IllegalArgumentException(
              s"Value of activityType $wrongValue is unexpected."
            )
        }
        val operationDurationInSec = get("operationDurationInSec").toDouble.round.toInt

        val payloadId = get("payloadId").createId[PayloadPlan]
        val locationX = row.get("locationX")
        val locationY = row.get("locationY")

        extractProjectedCoordOrTaz(
          locationX,
          locationY,
          row.get("locationZone"),
          snapLocationAndRemoveInvalidInputs
        ) match {
          case (locationZoneMaybe, Right(coord)) =>
            Some(
              PayloadPlan(
                payloadId,
                get("sequenceRank").toDouble.round.toInt,
                get("tourId").createId,
                get("payloadType").createId[PayloadType],
                get("weightInKg").toDouble,
                activityType,
                locationZoneMaybe,
                coord,
                get("estimatedTimeOfArrivalInSec").toDouble.toInt,
                get("arrivalTimeWindowInSecLower").toDouble.toInt,
                get("arrivalTimeWindowInSecUpper").toDouble.toInt,
                operationDurationInSec
              )
            )
          case (_, Left(error)) =>
            errors.append(
              ErrorInfo(
                payloadId.toString,
                Category.FreightPayloadPlan,
                error,
                locationX.toDouble,
                locationY.toDouble
              )
            )
            None
        }
      }

    outputDirMaybe.foreach { path =>
      if (errors.isEmpty) logger.info("No 'snap location' error to report for freight payload plans.")
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.FreightPayloadPlans}", errors)
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
    val freightVehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType] = config.vehicleTypesFilePath match {
      case Some(filePath) => vehicleTypes ++ readBeamVehicleTypeFile(filePath)
      case None           => vehicleTypes
    }
    // ****
    val existingAllTours: Set[Id[FreightTour]] = allTours.keySet.intersect(allPlans.map(_._2.tourId).toSet)
    val sampledToursNum = (allTours.size * config.tourSampleSizeAsFractionOfTotal).round.toInt
    val sampledTours = rnd.shuffle(allTours).take(sampledToursNum).toMap
    logger.info(s"Freight after sampling: ${sampledTours.size} tours")
    val existingSampledTours: Set[Id[FreightTour]] = sampledTours.keySet.intersect(allPlans.map(_._2.tourId).toSet)
    val plans: Map[Id[PayloadPlan], PayloadPlan] = allPlans.filter { case (_, plan) =>
      existingSampledTours.contains(plan.tourId)
    }
    val tourIdToPlans: Map[Id[FreightTour], IndexedSeq[PayloadPlan]] =
      plans.values.toIndexedSeq.groupBy(_.tourId).map { case (tourId, plans) => tourId -> plans.sortBy(_.sequenceRank) }
    val tours: Map[Id[FreightTour], FreightTour] = sampledTours.filter { case (_, tour) =>
      existingSampledTours.contains(tour.tourId)
    }

    case class FreightCarrierRow(
      carrierId: Id[FreightCarrier],
      tourId: Id[FreightTour],
      vehicleId: Id[BeamVehicle],
      vehicleTypeId: Id[BeamVehicleType],
      depotLocationZone: Option[Id[TAZ]],
      depotLocationUTM: Coord
    )

    def createCarrierVehicles(
      carrierId: Id[FreightCarrier],
      carrierRows: IndexedSeq[FreightCarrierRow],
      depotLocationUTM: Coord
    ): IndexedSeq[BeamVehicle] = {
      val vehicles: IndexedSeq[BeamVehicle] = carrierRows
        .filterNot(_.vehicleId == NO_VEHICLE_ID)
        .groupBy(_.vehicleId)
        .map { case (vehicleId, rows) =>
          val firstRow = rows.head
          val vehicleType = freightVehicleTypes.getOrElse(
            firstRow.vehicleTypeId,
            throw new IllegalArgumentException(
              s"Vehicle type for vehicle $vehicleId not found: ${firstRow.vehicleTypeId}"
            )
          )
          if (vehicleType.payloadCapacityInKg.isEmpty)
            throw new IllegalArgumentException(
              s"Vehicle type ${firstRow.vehicleTypeId} for vehicle $vehicleId has no payloadCapacityInKg defined"
            )
          createFreightVehicle(vehicleId, vehicleType, carrierId, depotLocationUTM, rnd.nextInt())
        }
        .toIndexedSeq
      vehicles
    }

    def createCarrier(carrierId: Id[FreightCarrier], carrierRows: IndexedSeq[FreightCarrierRow]) = {
      val depotLocationUTM: Coord = carrierRows.head.depotLocationUTM
      val depotLocationZone: Option[Id[TAZ]] = carrierRows.head.depotLocationZone
      val vehicleMap: Map[Id[BeamVehicle], BeamVehicle] = {
        val vehicles: IndexedSeq[BeamVehicle] = createCarrierVehicles(carrierId, carrierRows, depotLocationUTM)
        vehicles.map(vehicle => vehicle.id -> vehicle).toMap
      }

      val tourMap: Map[Id[BeamVehicle], IndexedSeq[FreightTour]] = carrierRows
        .groupBy(_.vehicleId)
        .mapValues {
          _
            //setting the tour depot location to be the carrier depot location
            .map(row => tours(row.tourId))
            .sortBy(_.departureTimeInSec)
        }

      val carrierTourIds = tourMap.values.flatten.map(_.tourId).toSet

      val plansPerTour: Map[Id[FreightTour], IndexedSeq[PayloadPlan]] =
        carrierTourIds.collect {
          case tourId if tourIdToPlans.contains(tourId) => tourId -> tourIdToPlans(tourId)
        }.toMap
      val carrierPlanIds: Set[Id[PayloadPlan]] = plansPerTour.values.flatten.map(_.payloadId).toSet
      val payloadMap = carrierPlanIds.map(planId => planId -> plans(planId)).toMap
      val fleetDistribution: Map[BeamVehicleType, Double] =
        calculateFreightDistribution(vehicleMap).iterator.map { case (vehicleTypeId, share) =>
          vehicleTypes(vehicleTypeId) -> share
        }.toMap

      FreightCarrier(
        carrierId,
        tourMap,
        payloadMap,
        vehicleMap,
        fleetDistribution,
        plansPerTour,
        depotLocationZone,
        depotLocationUTM
      )
    }

    def calculateFreightDistribution(fleet: Map[Id[BeamVehicle], BeamVehicle]): Map[Id[BeamVehicleType], Double] = {
      if (fleet.isEmpty) Map.empty
      else {
        val total = fleet.size.toDouble
        val counts = new scala.collection.mutable.HashMap[Id[BeamVehicleType], Int]()
        fleet.valuesIterator.foreach { vehicle =>
          val key = vehicle.beamVehicleType.id
          counts.update(key, counts.getOrElse(key, 0) + 1)
        }
        counts.map { case (k, v) => (k, v / total) }.toMap
      }
    }

    val errors: ListBuffer[ErrorInfo] = ListBuffer()

    val maybeCarrierRows = GenericCsvReader.readAsSeq[Option[FreightCarrierRow]](config.carriersFilePath) { row =>
      def get(key: String): String = getRowValue(config.carriersFilePath, row, key)

      //carrierId,tourId,vehicleId,vehicleTypeId,depotZone,depotX,depotY
      val vehicleIdStr = get("vehicleId")
      val isOnDemandShipment = vehicleIdStr == null || vehicleIdStr.isBlank
      val carrierIdStr = get("carrierId")
      val carrierId: Id[FreightCarrier] =
        if (isOnDemandShipment) {
          if (carrierIdStr == null || carrierIdStr.isBlank) NO_CARRIER_ID else carrierIdStr.createId
        } else if (carrierIdStr.startsWith(s"$FREIGHT_ID_PREFIX-")) carrierIdStr.createId
        else s"$FREIGHT_ID_PREFIX-$carrierIdStr".createId
      val tourId: Id[FreightTour] = get("tourId").createId
      val vehicleId: Id[BeamVehicle] =
        if (isOnDemandShipment) NO_VEHICLE_ID
        else if (vehicleIdStr.startsWith(s"$FREIGHT_ID_PREFIX-")) vehicleIdStr.createId
        else s"$FREIGHT_ID_PREFIX-$vehicleIdStr".createId
      val vehicleTypeId: Id[BeamVehicleType] =
        if (isOnDemandShipment) "no-type".createId else get("vehicleTypeId").createId
      if (!existingAllTours.contains(tourId)) {
        logger.error(f"Following freight carrier row discarded because tour $tourId was filtered out: $row")
        None
      } else if (!existingSampledTours.contains(tourId)) {
        logger.debug(f"Following freight carrier row ignored because tour $tourId was sampled out: $row")
        None
      } else {
        val depotX = row.get("depotX")
        val depotY = row.get("depotY")

        extractProjectedCoordOrTaz(
          row.get("depotX"),
          row.get("depotY"),
          row.get("depotZone"),
          snapLocationAndRemoveInvalidInputs
        ) match {
          case (depotZoneMaybe, Right(coord)) =>
            Some(FreightCarrierRow(carrierId, tourId, vehicleId, vehicleTypeId, depotZoneMaybe, coord))
          case (_, Left(error)) =>
            errors.append(
              ErrorInfo(
                carrierId.toString,
                Category.FreightCarrier,
                error,
                depotX.toDouble,
                depotY.toDouble
              )
            )
            None
        }
      }
    }

    outputDirMaybe.foreach { path =>
      if (errors.isEmpty) logger.info("No 'snap location' error to report for freight carriers.")
      else SnapCoordinateUtils.writeToCsv(s"$path/${CsvFile.FreightCarriers}", errors)
    }

    val removedCarrierIds = errors.map(_.id)
    val carriersWithFleet = maybeCarrierRows.flatten
      .groupBy(_.carrierId)
      .filterNot { case (carrierId, _) => removedCarrierIds.contains(carrierId.toString) }
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

  private def extractProjectedCoordOrTaz(
    strX: String,
    strY: String,
    strZone: String,
    snapLocationAndRemoveInvalidInputs: Boolean
  ): (Option[Id[TAZ]], SnapCoordinateResult) = {
    if (isBlank(strX) || isBlank(strY)) {
      val taz = getTaz(strZone)
      val coord =
        if (snapLocationAndRemoveInvalidInputs) TAZTreeMap.randomLocationInTAZ(taz, rnd, snapLocationHelper)
        else TAZTreeMap.randomLocationInTAZ(taz, rnd)
      (Some(taz.tazId), Right(coord))
    } else {
      val loc = new Coord(strX.toDouble, strY.toDouble)
      val locInUtm = if (config.isWgs) geoUtils.wgs2Utm(loc) else loc
      val newLocIntUtm = networkMaybe.map(NetworkUtils.getNearestLink(_, locInUtm).getCoord).getOrElse(locInUtm)
      val coordInUtm =
        if (snapLocationAndRemoveInvalidInputs) snapLocationHelper.computeResult(newLocIntUtm)
        else Right(locInUtm)
      (None, coordInUtm)
    }
  }

  @Override
  def createPersonId(vehicleId: Id[BeamVehicle]): Id[Person] = vehicleId.toString.createId

  @Override
  def createHouseholdId(carrierId: Id[FreightCarrier]): Id[Household] = carrierId.toString.createId

}
