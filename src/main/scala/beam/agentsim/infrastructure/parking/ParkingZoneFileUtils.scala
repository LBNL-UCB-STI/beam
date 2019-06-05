package beam.agentsim.infrastructure.parking

import java.io.{BufferedReader, File, IOException}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex
import scala.collection.JavaConverters._

import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearch
import beam.agentsim.infrastructure.taz.TAZ
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils

// utilities to read/write parking zone information from/to a file
object ParkingZoneFileUtils extends LazyLogging {

  /**
    * used to parse a row of the parking file
    * last row (ReservedFor) is ignored
    */
  val ParkingFileRowRegex: Regex = """(\w+),(\w+),(\w+),(\w.+),(\d+),(\d+).*""".r.unanchored

  /**
    * header for parking files (used for writing new parking files)
    */
  val ParkingFileHeader: String = "taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor"

  /**
    * when a parking file is not provided, we generate one that covers all TAZs with free and ubiquitous parking
    * this should consider charging when it is implemented as well.
    * @param tazId a valid id for a TAZ
    * @param parkingType the parking type we are using to generate a row
    * @return a row describing infinite free parking at this TAZ
    */
  def defaultParkingRow(tazId: String, parkingType: ParkingType): String =
    s"$tazId,$parkingType,${PricingModel.FlatFee(0, 0)},None,${Int.MaxValue},0,unused"

  /**
    * used to build up parking alternatives from a file
    * @param zones the parking zones read in
    * @param tree the search tree constructed from the loaded zones
    * @param totalRows number of rows read
    * @param failedRows number of rows which failed to parse
    */
  case class ParkingLoadingAccumulator(
    zones: Array[ParkingZone] = Array.empty[ParkingZone],
    tree: ZoneSearch[TAZ] = Map.empty[Id[TAZ], Map[ParkingType, List[Int]]],
    totalRows: Int = 0,
    failedRows: Int = 0
  ) {

    def countFailedRow: ParkingLoadingAccumulator =
      this.copy(
        totalRows = totalRows + 1,
        failedRows = failedRows + 1
      )
    def nextParkingZoneId: Int = zones.length
    def someRowsFailed: Boolean = failedRows > 0
  }

  /**
    * parking data associated with a row of the parking file
    * @param tazId a TAZ id
    * @param parkingType the parking type of this row
    * @param parkingZone the parking zone produced by this row
    */
  case class ParkingLoadingDataRow(tazId: Id[TAZ], parkingType: ParkingType, parkingZone: ParkingZone)

  /**
    * write the loaded set of parking and charging options to an instance parking file
    *
    * @param stallSearch the search tree of available parking options
    * @param stalls the stored ParkingZones
    * @param writeDestinationPath a file path to write to
    */
  def writeParkingZoneFile(
    stallSearch: ZoneSearch[TAZ],
    stalls: Array[ParkingZone],
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
          case Some(pm) => (s"$pm", s"${pm.cost}")
        }
        val chargingPoint = parkingZone.chargingPointType match {
          case None     => ""
          case Some(cp) => s"$cp"
        }

        s"$tazId,$parkingType,$pricingModel,$chargingPoint,${parkingZone.maxStalls},$feeInCents,"
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
    * @param header whether or not the file is expected to have a csv header row
    * @return table and tree
    */
  def fromFile(filePath: String, header: Boolean = true): (Array[ParkingZone], ZoneSearch[TAZ]) =
    Try {
      val reader = IOUtils.getBufferedReader(filePath)
      if (header) reader.readLine()
      reader
    } match {
      case Success(reader) =>
        val parkingLoadingAccumulator: ParkingLoadingAccumulator = fromBufferedReader(reader)
        logger.info(s"loaded ${parkingLoadingAccumulator.totalRows} zonal parking options from file $filePath")
        if (parkingLoadingAccumulator.someRowsFailed) {
          logger.warn(s"${parkingLoadingAccumulator.failedRows} rows of parking data failed to load.")
        }
        (parkingLoadingAccumulator.zones, parkingLoadingAccumulator.tree)
      case Failure(e) =>
        throw new java.io.IOException(s"Unable to load parking configuration file with path $filePath.\n$e")
    }

  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    *
    * @param reader a java.io.BufferedReader of a csv file
    * @return ParkingZone array and tree lookup
    */
  def fromBufferedReader(reader: BufferedReader): ParkingLoadingAccumulator = {

    @tailrec
    def _read(
      accumulator: ParkingLoadingAccumulator = ParkingLoadingAccumulator()
    ): ParkingLoadingAccumulator = {
      val csvRow = reader.readLine()
      if (csvRow == null) accumulator
      else {
        val updatedAccumulator = parseParkingZoneFromRow(csvRow, accumulator.nextParkingZoneId) match {
          case None =>
            accumulator.countFailedRow
          case Some(row: ParkingLoadingDataRow) =>
            addStallToSearch(row, accumulator)
        }
        _read(updatedAccumulator)
      }
    }

    _read()
  }

  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    *
    * @param csvFileContents each line from a file to be read
    * @return table and search tree
    */
  def fromIterator(csvFileContents: Iterator[String], header: Boolean = true): ParkingLoadingAccumulator = {

    val maybeWithoutHeader = if (header) csvFileContents.drop(1) else csvFileContents

    maybeWithoutHeader.foldLeft(ParkingLoadingAccumulator()) { (accumulator, csvRow) =>
      Try {
        if (csvRow.trim == "") accumulator
        else {
          parseParkingZoneFromRow(csvRow, accumulator.nextParkingZoneId) match {
            case None =>
              accumulator.countFailedRow
            case Some(row: ParkingLoadingDataRow) =>
              addStallToSearch(row, accumulator)
          }
        }
      } match {
        case Success(updatedAccumulator) =>
          updatedAccumulator
        case Failure(e) =>
          logger.info(s"failed to load parking data row due to ${e.getMessage}. Original row: '$csvRow'")
          accumulator.countFailedRow
      }
    }
  }

  /**
    * parses a row of parking configuration into the data structures used to represent it
    * @param csvRow the comma-separated parking attributes
    * @return a ParkingZone and it's corresponding ParkingType and Taz Id
    */
  def parseParkingZoneFromRow(
    csvRow: String,
    nextParkingZoneId: Int
  ): Option[ParkingLoadingDataRow] = {
    csvRow match {
      case ParkingFileRowRegex(
          tazString,
          parkingTypeString,
          pricingModelString,
          chargingTypeString,
          numStallsString,
          feeInCentsString
          ) =>
        Try {

          // parse this row from the source file
          val taz = Id.create(tazString.toUpperCase, classOf[TAZ])
          val parkingType = ParkingType(parkingTypeString)
          val pricingModel = PricingModel(pricingModelString, feeInCentsString)
          val chargingPoint = ChargingPointType(chargingTypeString)
          val numStalls = numStallsString.toInt
          val parkingZone = ParkingZone(nextParkingZoneId, taz, numStalls, chargingPoint, pricingModel)

          ParkingLoadingDataRow(taz, parkingType, parkingZone)

        } match {
          case Success(updatedAccumulator) =>
            Some { updatedAccumulator }
          case Failure(e) =>
            throw new java.io.IOException(s"Failed to load parking data from row with contents '$csvRow'.\n$e")
        }
      case _ =>
        throw new java.io.IOException(s"Failed to match row of parking configuration '$csvRow' to expected schema")
    }
  }

  /**
    * a kind of lens-based update for the search tree
    *
    * @param row the row data we parsed from a file
    * @param accumulator the currently loaded zones and search tree
    * @return updated tree, stalls
    */
  private[ParkingZoneFileUtils] def addStallToSearch(
    row: ParkingLoadingDataRow,
    accumulator: ParkingLoadingAccumulator
  ): ParkingLoadingAccumulator = {

    // find any data stored already within this TAZ and with this ParkingType
    val parkingTypes = accumulator.tree.getOrElse(row.tazId, Map())
    val parkingZoneIds: List[Int] = parkingTypes.getOrElse(row.parkingType, List.empty[Int])

    // create new ParkingZone in array with new parkingZoneId. should this be an ArrayBuilder?
    val updatedStalls = accumulator.zones :+ row.parkingZone

    // update the tree with the id of this ParkingZone
    val updatedTree =
      accumulator.tree.updated(
        row.tazId,
        parkingTypes.updated(
          row.parkingType,
          parkingZoneIds :+ row.parkingZone.parkingZoneId
        )
      )

    ParkingLoadingAccumulator(updatedStalls, updatedTree, accumulator.totalRows + 1, accumulator.failedRows)
  }

  /**
    * generates ubiquitous parking from a taz centers file, such as test/input/beamville/taz-centers.csv
    * @param tazFilePath path to the taz-centers file
    * @return
    */
  def generateDefaultParkingFromTazfile(tazFilePath: String): (Array[ParkingZone], ZoneSearch[TAZ]) = {
    Try {
      IOUtils.getBufferedReader(tazFilePath)
    } match {
      case Success(reader) =>
        generateDefaultParking(reader.lines.iterator.asScala, header = true)
      case Failure(e) =>
        throw new java.io.IOException(s"Unable to load taz file with path $tazFilePath.\n$e")
    }
  }

  /**
    * the first column of the taz-centers file is an Id[Taz], which we extract. it can be alphanumeric.
    */
  val TazFileRegex = """^(\w+),""".r.unanchored

  /**
    * generates ubiquitous parking from the contents of a TAZ centers file
    * @param tazFileContents an iterator of lines from the TAZ centers file
    * @param header if the header row exists
    * @return parking zones and parking search tree
    */
  def generateDefaultParking(
    tazFileContents: Iterator[String],
    header: Boolean
  ): (Array[ParkingZone], ZoneSearch[TAZ]) = {
    val tazRows = if (header) tazFileContents.drop(1) else tazFileContents

    val rows: Iterator[String] = for {
      TazFileRegex(tazId) <- tazRows
      parkingType         <- Seq(ParkingType.Public, ParkingType.Residential, ParkingType.Workplace)
    } yield {
      defaultParkingRow(tazId, parkingType)
    }

    val ParkingLoadingAccumulator(zones, tree, _, _) = fromIterator(rows, header = false)

    (zones, tree)
  }
}
