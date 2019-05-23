package beam.agentsim.infrastructure.parking

import java.io.{BufferedReader, File, IOException}

import scala.annotation.tailrec
import scala.util.{Failure, Success, Try}
import scala.util.matching.Regex

import beam.agentsim.infrastructure.charging.ChargingPointType
import beam.agentsim.infrastructure.parking.ParkingZoneSearch.ZoneSearch
import beam.agentsim.infrastructure.taz.TAZ
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.io.IOUtils

// utilities to load parking zone information from a file
object ParkingZoneFileUtils extends LazyLogging {

  /**
    * write the loaded set of parking and charging options to an instance parking file
    *
    * @param stallSearch the search tree of available parking options
    * @param stalls the stored ParkingZones
    * @param writeDestinationPath a file path to write to
    */
  def writeParkingZoneFile(
    stallSearch: ZoneSearch,
    stalls: Array[ParkingZone],
    writeDestinationPath: String
  ): Unit = {

    val header: String = "taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor"
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
        val newlineFormattedCSVOutput: String = (List(header) ::: rows).mkString("\n")
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
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls.
    *
    * the Array[ParkingZone] should be a private member of at most one Actor to prevent race conditions.
    *
    * @param filePath location in FS of taz parking data file (.csv) with a header row
    * @return table and tree
    */
  def fromFile(filePath: String, header: Boolean = true): (Array[ParkingZone], ZoneSearch) =
    Try {
      val reader = IOUtils.getBufferedReader(filePath)
      if (header) reader.readLine()
      reader
    } match {
      case Success(reader) =>
        val (stalls, tree) = fromBufferedReader(reader)
        logger.info(s"loaded ${stalls.length} zonal parking options from file $filePath")
        (stalls, tree)
      case Failure(e) =>
        throw new java.io.IOException(s"Unable to load parking configuration file with path $filePath.\n$e")
    }

  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    *
    * @param reader a java.io.BufferedReader of a csv file
    * @return ParkingZone array and tree lookup
    */
  def fromBufferedReader(reader: BufferedReader): (Array[ParkingZone], ZoneSearch) = {

    @tailrec
    def _read(
      stallTable: Array[ParkingZone] = Array.empty[ParkingZone],
      searchTree: ZoneSearch = Map.empty[Id[TAZ], Map[ParkingType, List[Int]]]
    ): (Array[ParkingZone], ZoneSearch) = {
      val csvRow = reader.readLine()
      if (csvRow == null) (stallTable, searchTree)
      else {
        val nextParkingZoneId: Int = stallTable.length
        val (tazId, parkingType, parkingZone) = parseParkingZoneFromRow(csvRow, nextParkingZoneId)
        val (updatedStallTable, updatedSearchTree) =
          addStallToSearch(tazId, parkingType, parkingZone, searchTree, stallTable)
        _read(updatedStallTable, updatedSearchTree)
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
  def fromIterator(csvFileContents: Iterator[String], header: Boolean = true): (Array[ParkingZone], ZoneSearch) = {

    val accumulator = (Array.empty[ParkingZone], Map.empty[Id[TAZ], Map[ParkingType, List[Int]]]: ZoneSearch)

    val maybeWithoutHeader = if (header) csvFileContents.drop(1) else csvFileContents

    maybeWithoutHeader.foldLeft(accumulator) { (accumulator, csvRow) =>
      Try {
        if (csvRow.trim == "") accumulator
        else {
          val (stallTable, searchTree) = accumulator
          val nextParkingZoneId = stallTable.length
          val (tazId, parkingType, parkingZone) = parseParkingZoneFromRow(csvRow, nextParkingZoneId)
          addStallToSearch(tazId, parkingType, parkingZone, searchTree, stallTable)
        }
      } match {
        case Success(updatedAccumulator) =>
          updatedAccumulator
        case Failure(e) =>
          throw new java.io.IOException(s"Failed to load parking data from row with contents '$csvRow'.\n$e")
      }
    }
  }

  private[ParkingZoneFileUtils] val ParkingFileRowRegex: Regex = "^(\\w+),(\\w+),(\\w+),(\\w+),(\\d+),(\\d+),(\\w+)$".r

  /**
    * parses a row of parking configuration into the data structures used to represent it
    * @param csvRow the comma-separated parking attributes
    * @return a ParkingZone and it's corresponding ParkingType and Taz Id
    */
  private[ParkingZoneFileUtils] def parseParkingZoneFromRow(
    csvRow: String,
    nextParkingZoneId: Int
  ): (Id[TAZ], ParkingType, ParkingZone) = {
    csvRow match {
      case ParkingFileRowRegex(
          tazString,
          parkingTypeString,
          pricingModelString,
          chargingTypeString,
          numStallsString,
          feeInCentsString,
          _
          ) =>
        Try {

          // parse this row from the source file
          val taz = Id.create(tazString.toUpperCase, classOf[TAZ])
          val parkingType = ParkingType(parkingTypeString)
          val pricingModel = PricingModel(pricingModelString, feeInCentsString)
          val chargingPoint = ChargingPointType(chargingTypeString)
          val numStalls = numStallsString.toInt
          val parkingZone = ParkingZone(nextParkingZoneId, numStalls, chargingPoint, pricingModel)

          (taz, parkingType, parkingZone)

        } match {
          case Success(updatedAccumulator) =>
            updatedAccumulator
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
    * @param tazId TAZ where this ParkingZone exists
    * @param parkingType a parking type category we are adding/updating
    * @param parkingZone the new parking zone we are adding to the simulation
    * @param tree the search tree we will update
    * @param stalls the collection of stall data we will update
    * @return updated tree, stalls
    */
  private[ParkingZoneFileUtils] def addStallToSearch(
    tazId: Id[TAZ],
    parkingType: ParkingType,
    parkingZone: ParkingZone,
    tree: ZoneSearch,
    stalls: Array[ParkingZone]
  ): (Array[ParkingZone], ZoneSearch) = {

    // find any data stored already within this TAZ and with this ParkingType
    val parkingTypes = tree.getOrElse(tazId, Map())
    val parkingZoneIds: List[Int] = parkingTypes.getOrElse(parkingType, List.empty[Int])

    // create new ParkingZone in array with new parkingZoneId. should this be an ArrayBuilder?
    val updatedStalls = stalls :+ parkingZone

    // update the tree with the id of this ParkingZone
    val updatedTree =
      tree.updated(
        tazId,
        parkingTypes.updated(
          parkingType,
          parkingZoneIds :+ parkingZone.parkingZoneId
        )
      )

    (updatedStalls, updatedTree)
  }
}
