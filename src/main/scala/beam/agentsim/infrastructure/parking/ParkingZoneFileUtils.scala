package beam.agentsim.infrastructure.parking

import beam.agentsim.agents.vehicles.VehicleCategory.VehicleCategory
import beam.agentsim.agents.vehicles.{VehicleCategory, VehicleManager}
import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearchTree
import beam.sim.config.BeamConfig
import beam.utils.FileUtils
import beam.utils.csv.GenericCsvReader
import beam.utils.logging.ExponentialLazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils

import java.io.{BufferedReader, File, IOException}
import scala.annotation.tailrec
import scala.collection.mutable
import scala.util.{Failure, Random, Success, Try}

// utilities to read/write parking zone information from/to a file
object ParkingZoneFileUtils extends ExponentialLazyLogging {

  type jMap = java.util.Map[String, String]

  /**
    * header for parking files (used for writing new parking files)
    */
  val ParkingFileHeader: String = List(
    "taz",
    "parkingType",
    "pricingModel",
    "chargingPointType",
    "numStalls",
    "feeInCents",
    "parkingZoneName",
    "landCostInUSDPerSqft",
    "reservedFor",
    "timeRestrictions",
    "parkingZoneId"
  ).mkString(",")

  /**
    * when a parking file is not provided, we generate one that covers all TAZs with free and ubiquitous parking
    * this should consider charging when it is implemented as well.
    * @param geoId a valid id for a geo object
    * @param parkingType the parking type we are using to generate a row
    * @param maybeChargingPoint charging point type
    * @return a row describing infinite free parking at this TAZ
    */
  def defaultParkingRow[GEO](
    geoId: Id[GEO],
    parkingType: ParkingType,
    maybeChargingPoint: Option[ChargingPointType],
    parkingManagerId: Id[VehicleManager]
  ): String =
    List(
      geoId.toString,
      parkingType.toString,
      PricingModel.FlatFee(0).toString,
      maybeChargingPoint.map(_.toString).getOrElse("NoCharger"),
      ParkingZone.UbiqiutousParkingAvailability.toString,
      "0",
      "",
      "",
      parkingManagerId.toString,
      "",
      ""
    ).mkString(",")

  /**
    * used to build up parking alternatives from a file
    * @param zones the parking zones read in
    * @param tree the search tree constructed from the loaded zones
    * @param totalRows number of rows read
    * @param failedRows number of rows which failed to parse
    */
  case class ParkingLoadingAccumulator[GEO](
    zones: mutable.Map[Id[ParkingZoneId], ParkingZone[GEO]] = mutable.Map.empty[Id[ParkingZoneId], ParkingZone[GEO]],
    tree: mutable.Map[Id[GEO], Map[ParkingType, Vector[Id[ParkingZoneId]]]] =
      mutable.Map.empty[Id[GEO], Map[ParkingType, Vector[Id[ParkingZoneId]]]],
    totalRows: Int = 0,
    failedRows: Int = 0
  ) {

    def countFailedRow: ParkingLoadingAccumulator[GEO] =
      this.copy(
        totalRows = totalRows + 1,
        failedRows = failedRows + 1
      )

    def someRowsFailed: Boolean = failedRows > 0

    def totalParkingStalls: Long = zones.map { _._2.maxStalls.toLong }.sum

    def parkingStallsPlainEnglish: String = {
      val count: Long = totalParkingStalls
      if (count > 1000000000L) s"${count / 1000000000L} billion"
      else if (count > 1000000L) s"${count / 1000000L} million"
      else if (count > 1000L) s"${count / 1000L} thousand"
      else count.toString
    }

  }

  /**
    * parking data associated with a row of the parking file
    * @param tazId a TAZ id
    * @param parkingType the parking type of this row
    * @param parkingZone the parking zone produced by this row
    */
  case class ParkingLoadingDataRow[GEO](tazId: Id[GEO], parkingType: ParkingType, parkingZone: ParkingZone[GEO])

  /**
    * write the loaded set of parking and charging options to an instance parking file
    *
    * @param stallSearch the search tree of available parking options
    * @param stalls the stored ParkingZones
    * @param writeDestinationPath a file path to write to
    */
  def writeParkingZoneFile[GEO](
    stallSearch: ZoneSearchTree[GEO],
    stalls: Map[Id[ParkingZoneId], ParkingZone[GEO]],
    writeDestinationPath: String
  ): Unit = {

    val destinationFile = new File(writeDestinationPath)

    Try {
      for {
        (tazId, parkingTypesSubtree)  <- stallSearch.toList
        (parkingType, parkingZoneIds) <- parkingTypesSubtree.toList
        parkingZoneId                 <- parkingZoneIds
      } yield {

        val parkingZone = stalls(parkingZoneId)
        val (pricingModel, feeInCents) = parkingZone.pricingModel match {
          case None     => ("", "")
          case Some(pm) => (s"$pm", s"${pm.costInDollars / 100.0}")
        }
        val chargingPoint = parkingZone.chargingPointType match {
          case None     => "NoCharger"
          case Some(cp) => s"$cp"
        }
        val parkingZoneName = parkingZone.parkingZoneName.getOrElse("")
        val landCostInUSDPerSqft = parkingZone.landCostInUSDPerSqft.getOrElse("")
        val reservedFor = parkingZone.reservedFor.toString.mkString("|")
        val timeRestrictions = parkingZone.timeRestrictions.map(toString).mkString("|")
        val parkingZoneIdStr = parkingZone.parkingZoneId.toString
        List(
          tazId.toString,
          parkingType.toString,
          pricingModel,
          chargingPoint,
          parkingZone.maxStalls,
          feeInCents,
          parkingZoneName,
          landCostInUSDPerSqft,
          reservedFor,
          timeRestrictions,
          parkingZoneIdStr
        ).mkString(",")
      }
    } match {
      case Failure(e) =>
        throw new RuntimeException(s"failed while converting parking configuration to csv format.\n$e")
      case Success(rows) =>
        val newlineFormattedCSVOutput: String = (List(ParkingFileHeader) ::: rows).mkString("\n")
        Try {
          destinationFile.getParentFile.mkdirs()
          val writer = IOUtils.getBufferedWriter(writeDestinationPath)
          writer.write(newlineFormattedCSVOutput)
          writer.close()
        } match {
          case Failure(e) =>
            throw new IOException(s"failed while writing parking configuration to file $writeDestinationPath.\n$e")
          case Success(_) =>
        }
    }
  }

  /**
    * loads taz parking data from file, creating an array of parking zones along with a search tree to find zones.
    *
    * the Array[ParkingZone] should be a private member of at most one Actor to prevent race conditions.
    *
    * @param filePath location in FS of taz parking data file (.csv)
    * @return table and tree
    */
  def fromFile[GEO: GeoLevel](
    filePath: String,
    rand: Random,
    beamConfig: Option[BeamConfig],
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0
  ): (Map[Id[ParkingZoneId], ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val parkingLoadingAccumulator =
      fromFileToAccumulator(
        filePath,
        rand,
        beamConfig,
        parkingStallCountScalingFactor,
        parkingCostScalingFactor
      )
    (parkingLoadingAccumulator.zones.toMap, parkingLoadingAccumulator.tree)
  }

  /**
    * Loads taz parking data from file, creating a parking zone accumulator
    * This method allows to read multiple parking files into a single array of parking zones
    * along with a single search tree to find zones
    *
    * @param filePath location in FS of taz parking data file (.csv)
    * @return parking zone accumulator
    */
  def fromFileToAccumulator[GEO: GeoLevel](
    filePath: String,
    rand: Random,
    beamConfig: Option[BeamConfig],
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    parkingLoadingAcc: ParkingLoadingAccumulator[GEO] = ParkingLoadingAccumulator[GEO]()
  ): ParkingLoadingAccumulator[GEO] = {
    FileUtils.using(FileUtils.getReader(filePath)) { reader =>
      Try(
        fromBufferedReader(
          reader,
          rand,
          beamConfig,
          parkingStallCountScalingFactor,
          parkingCostScalingFactor,
          parkingLoadingAcc
        )
      ) match {
        case Success(parkingLoadingAccumulator) =>
          logger.info(
            s"loaded ${parkingLoadingAccumulator.totalRows} rows as parking zones from $filePath, with ${parkingLoadingAccumulator.parkingStallsPlainEnglish} stalls (${parkingLoadingAccumulator.totalParkingStalls}) in system"
          )
          if (parkingLoadingAccumulator.someRowsFailed) {
            logger.warn(s"${parkingLoadingAccumulator.failedRows} rows of parking data failed to load")
          }
          parkingLoadingAccumulator
        case Failure(e) =>
          logger.error("Failure", e)
          throw new java.io.IOException(s"Unable to load parking configuration file with path $filePath.\n$e")
      }
    }
  }

  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    *
    * @param reader a java.io.BufferedReader of a csv file
    * @return ParkingZone array and tree lookup
    */
  def fromBufferedReader[GEO: GeoLevel](
    reader: BufferedReader,
    rand: Random,
    beamConfig: Option[BeamConfig],
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    parkingLoadingAccumulator: ParkingLoadingAccumulator[GEO] = ParkingLoadingAccumulator()
  ): ParkingLoadingAccumulator[GEO] = {

    val (iterator, closable) = GenericCsvReader.readFromReaderAs[jMap](reader, identity)
    @tailrec
    def _read(
      accumulator: ParkingLoadingAccumulator[GEO]
    ): ParkingLoadingAccumulator[GEO] = {
      if (iterator.hasNext) {
        val csvRow = iterator.next()
        val updatedAccumulator = parseParkingZoneFromRow(
          csvRow,
          rand,
          beamConfig,
          None,
          parkingStallCountScalingFactor,
          parkingCostScalingFactor
        ) match {
          case None =>
            accumulator.countFailedRow
          case Some(row: ParkingLoadingDataRow[GEO]) =>
            addStallToSearch(row, accumulator)
        }
        _read(updatedAccumulator)
      } else {
        accumulator
      }
    }

    try {
      _read(parkingLoadingAccumulator)
    } finally {
      closable.close()
    }
  }

  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    *
    * @param csvFileContents each line from a file to be read
    * @return table and search tree
    */
  def fromIterator[GEO: GeoLevel](
    csvFileContents: Iterator[String],
    beamConfig: Option[BeamConfig],
    random: Random = Random,
    parkingManagerId: Option[Id[VehicleManager]] = None,
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0,
    parkingLoadingAcc: ParkingLoadingAccumulator[GEO] = ParkingLoadingAccumulator[GEO]()
  ): ParkingLoadingAccumulator[GEO] = {

    val withLineBreaks = csvFileContents.filterNot(_.trim.isEmpty).flatMap(x => Seq(x, "\n"))
    val (iterator, closable) =
      GenericCsvReader.readFromReaderAs[jMap](FileUtils.readerFromIterator(withLineBreaks), identity)

    try {
      iterator.foldLeft(parkingLoadingAcc) { (accumulator, csvRow) =>
        Try {
          parseParkingZoneFromRow(
            csvRow,
            random,
            beamConfig,
            parkingManagerId,
            parkingStallCountScalingFactor,
            parkingCostScalingFactor
          ) match {
            case None =>
              accumulator.countFailedRow
            case Some(row: ParkingLoadingDataRow[GEO]) =>
              addStallToSearch(row, accumulator)
          }
        } match {
          case Success(updatedAccumulator) =>
            updatedAccumulator
          case Failure(e) =>
            logger.info(s"failed to load parking data row due to ${e.getMessage}. Original row: '$csvRow'")
            accumulator.countFailedRow
        }
      }
    } finally {
      closable.close()
    }
  }

  /**
    * Creating search tree to find stalls from a sequence of Parking zones
    *
    * @param zones each line from a file to be read
    * @return table and search tree
    */
  def createZoneSearchTree[GEO](zones: Seq[ParkingZone[GEO]]): ZoneSearchTree[GEO] = {

    zones.foldLeft(Map.empty: ZoneSearchTree[GEO]) { (accumulator, zone) =>
      val parkingTypes = accumulator.getOrElse(zone.geoId, Map())
      val parkingZoneIds: Vector[Id[ParkingZoneId]] =
        parkingTypes.getOrElse(zone.parkingType, Vector.empty[Id[ParkingZoneId]])

      accumulator.updated(
        zone.geoId,
        parkingTypes.updated(
          zone.parkingType,
          (parkingZoneIds :+ zone.parkingZoneId).sorted
        )
      )
    }
  }

  private val TimeRestriction = """(\w+)\|(\d{1,2})(?::(\d{2}))?-(\d{1,2})(?::(\d{2}))?""".r

  private[parking] def parseTimeRestrictions(timeRestrictionsString: String): Map[VehicleCategory, Range] = {

    def parseTimeRestriction(timeRestrictionString: String): Option[(VehicleCategory, Range)] = {
      timeRestrictionString match {
        case TimeRestriction(
              categoryStr,
              hour1,
              minute1,
              hour2,
              minute2
            ) =>
          val category = VehicleCategory.fromString(categoryStr)
          val from = hour1.toInt * 3600 + Option(minute1).map(_.toInt).getOrElse(0) * 60
          val to = hour2.toInt * 3600 + Option(minute2).map(_.toInt).getOrElse(0) * 60
          Some(category -> Range(from, to))
        case _ =>
          logger.error(s"Cannot parse time restriction data: $timeRestrictionString")
          None
      }
    }

    // values look like LightDutyTruck:00:00-14:00|Car:14:00-18:00|Bike:18:00-24:00
    Option(timeRestrictionsString)
      .getOrElse("")
      .split(';')
      .map(_.trim)
      .filterNot(_.isEmpty)
      .flatMap(parseTimeRestriction)
      .toMap

  }

  private def toString(restriction: (VehicleCategory, Range)): String = {
    val (category, range) = restriction
    val fromHour = range.start / 3600
    val fromMin = range.start % 3600 / 60
    val toHour = range.end / 3600
    val toMin = range.end % 3600 / 60
    "%s:%d:%02d-%d:%02d".format(category, fromHour, fromMin, toHour, toMin)
  }

  /**
    * parses a row of parking configuration into the data structures used to represent it
    *
    * @param csvRow the comma-separated parking attributes
    * @return a ParkingZone and it's corresponding ParkingType and Taz Id
    */
  def parseParkingZoneFromRow[GEO: GeoLevel](
    csvRow: jMap,
    rand: Random,
    beamConfig: Option[BeamConfig],
    parkingManagerId: Option[Id[VehicleManager]] = None,
    parkingStallCountScalingFactor: Double = 1.0,
    parkingCostScalingFactor: Double = 1.0
  ): Option[ParkingLoadingDataRow[GEO]] = {
    if (!validateCsvRow(csvRow)) {
      logger.error(s"Failed to match row of parking configuration '$csvRow' to expected schema")
      return None
    }
    val tazString = csvRow.get("taz")
    val parkingTypeString = csvRow.get("parkingType")
    val pricingModelString = csvRow.get("pricingModel")
    val chargingTypeString = csvRow.get("chargingPointType")
    val numStallsString = csvRow.get("numStalls")
    val feeInCentsString = csvRow.get("feeInCents")
    val parkingZoneNameString = csvRow.get("parkingZoneName")
    val landCostInUSDPerSqftString = csvRow.get("landCostInUSDPerSqft")
    val reservedForString = csvRow.get("reservedFor")
    val timeRestrictionsString = csvRow.get("timeRestrictions")
    val parkingZoneIdString = csvRow.get("parkingZoneId")
    Try {
      val feeInCents = feeInCentsString.toDouble
      val numStallsDouble = numStallsString.toDouble
      val newCostInDollarsString = (feeInCents * parkingCostScalingFactor / 100.0).toString
      val expectedNumberOfStalls = numStallsDouble * parkingStallCountScalingFactor
      val floorNumberOfStalls = math.floor(expectedNumberOfStalls).toInt
      val numberOfStallsToCreate = if ((expectedNumberOfStalls % 1.0) > rand.nextDouble) {
        floorNumberOfStalls + 1
      } else {
        floorNumberOfStalls
      }
      val reservedFor = validateReservedFor(reservedForString, beamConfig, parkingManagerId)
      // parse this row from the source file
      val taz = GeoLevel[GEO].parseId(tazString.toUpperCase)
      val parkingType = ParkingType(parkingTypeString)
      val pricingModel = PricingModel(pricingModelString, newCostInDollarsString)
      val timeRestrictions = parseTimeRestrictions(timeRestrictionsString)
      val chargingPoint = ChargingPointType(chargingTypeString)
      val numStalls = numberOfStallsToCreate
      val parkingZoneName =
        if (parkingZoneNameString == null || parkingZoneNameString.isEmpty) None else Some(parkingZoneNameString)
      val landCostInUSDPerSqft =
        if (landCostInUSDPerSqftString == null || landCostInUSDPerSqftString.isEmpty) None
        else Some(landCostInUSDPerSqftString.toDouble)
      val parkingZoneIdMaybe =
        if (parkingZoneIdString == null || parkingZoneIdString.isEmpty) None
        else Some(ParkingZone.createId(parkingZoneIdString))
      val parkingZone =
        ParkingZone.init(
          parkingZoneIdMaybe,
          taz,
          parkingType,
          reservedFor,
          numStalls,
          chargingPoint,
          pricingModel,
          timeRestrictions,
          parkingZoneName,
          landCostInUSDPerSqft
        )

      ParkingLoadingDataRow(taz, parkingType, parkingZone)

    } match {
      case Success(updatedAccumulator) =>
        Some { updatedAccumulator }
      case Failure(e) =>
        throw new java.io.IOException(s"Failed to load parking data from row with contents '$csvRow'.", e)
    }
  }

  private def validateReservedFor(
    reservedForString: String,
    beamConfigMaybe: Option[BeamConfig],
    parkingManagerId: Option[Id[VehicleManager]] = None
  ): Id[VehicleManager] = {
    if (beamConfigMaybe.isDefined) {
      val cfgAgentSim = beamConfigMaybe.get.beam.agentsim
      val vehicleManagerType =
        if (reservedForString == null || reservedForString.isEmpty || reservedForString == "Any")
          VehicleManager.BEAMCore
        else if (reservedForString == ParkingZone.GlobalReservedFor.toString) VehicleManager.BEAMCore
        else if (reservedForString == cfgAgentSim.agents.freight.name) VehicleManager.BEAMFreight
        else if (reservedForString == cfgAgentSim.agents.rideHail.name) VehicleManager.BEAMRideHail
        else if (cfgAgentSim.agents.vehicles.sharedFleets.exists(_.name == reservedForString)) VehicleManager.BEAMShared
        else {
          logger.warn(
            s"The following reservedFor value $reservedForString in parking file " +
            s"does not correspond to any known vehicle managers as predefined in the config file." +
            s"falling back to default manager"
          )
          VehicleManager.BEAMCore
        }
      if (vehicleManagerType == VehicleManager.BEAMCore)
        ParkingZone.GlobalReservedFor
      else
        VehicleManager.createOrGetIdUsingUnique(reservedForString, vehicleManagerType)
    } else if (parkingManagerId.isDefined) {
      parkingManagerId.get
    } else {
      logger.warn("BeamConfig is not defined, a default vehicle manager will be created")
      ParkingZone.GlobalReservedFor
    }
  }

  private def validateCsvRow(csvRow: jMap): Boolean = {
    val allRequiredPresented = Seq("taz", "parkingType", "pricingModel", "chargingPointType", "numStalls", "feeInCents")
      .forall(key => {
        val value = csvRow.get(key)
        value != null && value.nonEmpty
      })
    allRequiredPresented &&
    Try(csvRow.get("numStalls").toDouble).toOption.exists(_ >= 0) &&
    Try(csvRow.get("feeInCents").toDouble).toOption.exists(_ >= 0)
  }

  private def toCategories(categoryName: String, geoId: String): IndexedSeq[VehicleCategory.VehicleCategory] = {
    (if (categoryName == null) "" else categoryName).trim.toLowerCase match {
      //we had Any, Ridehail and RideHailManager in the taz-parking.csv files
      //allow the users not to modify existing files
      case "" | "any" => IndexedSeq.empty
      case value =>
        val maybeCategories =
          value
            .split('|')
            .map(categoryStr => categoryStr -> VehicleCategory.fromStringOptional(categoryStr))
            .toIndexedSeq
        maybeCategories.foreach { case (categoryStr, maybeCategory) =>
          if (maybeCategory.isEmpty) {
            logger.error(s"Wrong category '$categoryStr' for zone $geoId, ignoring the category")
          }
        }
        maybeCategories.flatMap { case (_, maybeCategory) => maybeCategory }
    }
  }

  /**
    * a kind of lens-based update for the search tree
    *
    * @param row the row data we parsed from a file
    * @param accumulator the currently loaded zones and search tree
    * @return updated tree, stalls
    */
  private[ParkingZoneFileUtils] def addStallToSearch[GEO](
    row: ParkingLoadingDataRow[GEO],
    accumulator: ParkingLoadingAccumulator[GEO]
  ): ParkingLoadingAccumulator[GEO] = {

    // find any data stored already within this TAZ and with this ParkingType
    val parkingTypes = accumulator.tree.getOrElse(row.tazId, Map())
    val parkingZoneIds: Vector[Id[ParkingZoneId]] =
      parkingTypes.getOrElse(row.parkingType, Vector.empty[Id[ParkingZoneId]])

    // create new ParkingZone in array with new parkingZoneId. should this be an ArrayBuilder?
    accumulator.zones.put(row.parkingZone.parkingZoneId, row.parkingZone)

    // update the tree with the id of this ParkingZone
    accumulator.tree.put(
      row.tazId,
      parkingTypes.updated(
        row.parkingType,
        (parkingZoneIds :+ row.parkingZone.parkingZoneId).sorted
      )
    )

    ParkingLoadingAccumulator(accumulator.zones, accumulator.tree, accumulator.totalRows + 1, accumulator.failedRows)
  }

  /**
    * generates ubiquitous parking from a taz centers file, such as test/input/beamville/taz-centers.csv
    * @param geoObjects geo objects that should be used to hold parking stalls
    * @param parkingTypes the parking types we are generating, by default, the complete set
    * @return
    */
  def generateDefaultParkingFromGeoObjects[GEO: GeoLevel](
    geoObjects: Iterable[GEO],
    random: Random,
    parkingManagerId: Id[VehicleManager],
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes
  ): (Map[Id[ParkingZoneId], ParkingZone[GEO]], ZoneSearchTree[GEO]) = {
    val parkingLoadingAccumulator =
      generateDefaultParkingAccumulatorFromGeoObjects(
        geoObjects,
        random,
        parkingManagerId,
        parkingTypes
      )
    (parkingLoadingAccumulator.zones.toMap, parkingLoadingAccumulator.tree)
  }

  /**
    * generates ubiquitous parking from a taz centers file, such as test/input/beamville/taz-centers.csv
    * @param geoObjects geo objects that should be used to hold parking stalls
    * @param parkingTypes the parking types we are generating, by default, the complete set
    * @return the parking accumulator
    */
  def generateDefaultParkingAccumulatorFromGeoObjects[GEO: GeoLevel](
    geoObjects: Iterable[GEO],
    random: Random,
    parkingManagerId: Id[VehicleManager],
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes,
    parkingLoadingAcc: ParkingLoadingAccumulator[GEO] = ParkingLoadingAccumulator[GEO]()
  ): ParkingLoadingAccumulator[GEO] = {
    val result =
      generateDefaultParking(geoObjects, random, parkingManagerId, parkingTypes, parkingLoadingAcc)
    logger.info(
      s"generated ${result.totalRows} parking zones,one for each provided geo level, with ${result.parkingStallsPlainEnglish} stalls (${result.totalParkingStalls}) in system"
    )
    if (result.someRowsFailed) {
      logger.warn(s"${result.failedRows} rows of parking data failed to load")
    }
    result
  }

  /**
    * generates ubiquitous parking from the contents of a TAZ centers file
    * @param geoObjects an iterable of geo objects
    * @param parkingTypes the parking types we are generating, by default, the complete set
    * @return parking zones and parking search tree
    */
  def generateDefaultParking[GEO: GeoLevel](
    geoObjects: Iterable[GEO],
    random: Random,
    parkingManagerId: Id[VehicleManager],
    parkingTypes: Seq[ParkingType] = ParkingType.AllTypes,
    parkingLoadingAcc: ParkingLoadingAccumulator[GEO] = ParkingLoadingAccumulator[GEO]()
  ): ParkingLoadingAccumulator[GEO] = {

    val rows: Iterable[String] = for {
      geoObj      <- geoObjects
      parkingType <- parkingTypes
      // We have to pass parking types: Some(CustomChargingPoint) and None
      // None is `NoCharger` which will allow non-charger ParkingZones. Check `returnSpotsWithoutChargers` in `ZonalParkingManager`
      maybeChargingPoint <- Seq(Some(ChargingPointType.CustomChargingPoint("DCFast", "50", "DC")), None) // NoCharger
    } yield {
      import GeoLevel.ops._
      defaultParkingRow(geoObj.getId, parkingType, maybeChargingPoint, parkingManagerId)
    }

    val withHeader = Iterator.single(ParkingFileHeader) ++ rows
    fromIterator(withHeader, None, random, Some(parkingManagerId), parkingLoadingAcc = parkingLoadingAcc)
  }

  /**
    * Write parking zones to csv.
    */
  def toCsv[GEO: GeoLevel](parkingZones: Map[Id[ParkingZoneId], ParkingZone[GEO]], filePath: String): Unit = {
    val fileContent = parkingZones
      .map { case (_, parkingZone) =>
        List(
          parkingZone.geoId,
          parkingZone.parkingType,
          parkingZone.pricingModel.getOrElse(""),
          parkingZone.chargingPointType.getOrElse(""),
          parkingZone.maxStalls,
          parkingZone.pricingModel.map(_.costInDollars).getOrElse(""),
          parkingZone.parkingZoneName.getOrElse(""),
          parkingZone.landCostInUSDPerSqft.getOrElse(""),
          parkingZone.reservedFor.toString.mkString("|"),
          parkingZone.timeRestrictions.map(x => x._1.toString + "|" + x._2.toString).mkString(";"),
          parkingZone.toString,
          parkingZone.parkingZoneId
        ).mkString(",")
      }
      .mkString(System.lineSeparator())

    FileUtils.writeToFile(filePath, Some(ParkingFileHeader), fileContent, None)
  }

}
