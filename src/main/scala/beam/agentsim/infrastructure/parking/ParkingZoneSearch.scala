package beam.agentsim.infrastructure.parking

import java.io.File

import scala.collection.Map
import scala.util.{Failure, Success, Try}

import beam.agentsim.infrastructure.taz.TAZ
import beam.agentsim.infrastructure.charging.ChargingInquiryData._
import beam.agentsim.infrastructure.charging._
import org.matsim.api.core.v01.Id

object ParkingZoneSearch {

  /**
    * a nested structure to support a search over available parking attributes,
    * where traversal either terminates in an un-defined branch (no options found),
    * or a leaf, which contains the index of a ParkingZone in the ParkingZones lookup array
    * with the matching attributes.
    */
  type StallSearch = Map[Id[TAZ], Map[ParkingType, List[Int]]]

  /**
    * find the best parking alternative for the data in this request
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param stalls stored ParkingZone data
    * @param costFunction ranking function for comparing options
    * @return the TAZ with the best ParkingZone and it's parkingZoneId
    */
  def find(
    chargingInquiryData: Option[ChargingInquiryData],
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: StallSearch,
    stalls: Array[ParkingZone],
    costFunction: (ParkingZone, Option[ChargingPreference]) => Double
  ): Option[(TAZ, ParkingType, ParkingZone, Int)] = {
    val found = findParkingZonesAndRanking(tazList, parkingTypes, tree, stalls)
    takeBestByRanking(found, chargingInquiryData, costFunction).map {
      case (taz, parkingType, parkingZone, id, _) =>
        (taz, parkingType, parkingZone, id)
    }
  }

  /**
    * look for matching ParkingZones, optionally based on charging infrastructure requirements, within a TAZ, which have vacancies
    * @param tazList the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param tree search tree of parking infrastructure
    * @param stalls stored ParkingZone data
    * @return list of discovered ParkingZone ids, corresponding to the Array[ParkingZone], ranked by the costFunction
    */
  def findParkingZonesAndRanking(
    tazList: Seq[TAZ],
    parkingTypes: Seq[ParkingType],
    tree: StallSearch,
    stalls: Array[ParkingZone]
  ): Seq[(ParkingZone, Int, TAZ, ParkingType)] = {

    // conduct search (toList required to combine Option and List monads)
    for {
      taz                 <- tazList
      parkingTypesSubtree <- tree.get(taz.tazId).toList
      parkingType         <- parkingTypes
      parkingZoneIds      <- parkingTypesSubtree.get(parkingType).toList
      parkingZoneId       <- parkingZoneIds
      if stalls(parkingZoneId).stallsAvailable > 0
    } yield {
      // get the zone
      Try {
        (stalls(parkingZoneId), parkingZoneId, taz, parkingType)
      } match {
        case Success(zone) => zone
        case Failure(e) =>
          throw new IndexOutOfBoundsException(s"Attempting to access ParkingZone with index $parkingZoneId failed.\n$e")
      }
    }
  }

  /**
    * finds the best parking zone id based on maximizing it's associated cost function evaluation
    * @param found the ranked parkingZones
    * @param costFunction ranking function for comparing options
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @return the best parking zone, it's id, and it's rank
    */
  def takeBestByRanking(
    found: Iterable[(ParkingZone, Int, TAZ, ParkingType)],
    chargingInquiryData: Option[ChargingInquiryData],
    costFunction: (ParkingZone, Option[ChargingPreference]) => Double
  ): Option[(TAZ, ParkingType, ParkingZone, Int, Double)] = {
    found.foldLeft(Option.empty[(TAZ, ParkingType, ParkingZone, Int, Double)]) { (bestZoneOption, parkingZoneTuple) =>
      val (thisParkingZone: ParkingZone, thisParkingZoneId: Int, thisTAZ: TAZ, thisParkingType: ParkingType) =
        parkingZoneTuple

      // rank this parking zone
      val thisRank = chargingInquiryData match {
        case None =>
          // not a charging vehicle
          costFunction(thisParkingZone, None)
        case Some(chargingData) =>
          // consider charging costs
          val pref: Option[ChargingPreference] = for {
            chargingPoint      <- thisParkingZone.chargingPoint
            chargingPreference <- chargingData.data.get(chargingPoint)
          } yield chargingPreference
          costFunction(thisParkingZone, pref)
      }

      // update fold accumulator with best-ranked parking zone along with relevant attributes
      bestZoneOption match {
        case None => Some { (thisTAZ, thisParkingType, thisParkingZone, thisParkingZoneId, thisRank) }
        case Some((_, _, _, _, bestRank)) =>
          if (bestRank < thisRank) Some { (thisTAZ, thisParkingType, thisParkingZone, thisParkingZoneId, thisRank) } else
            bestZoneOption
      }
    }
  }

  // utilities to load parking zone information from a file
  object io {

    /**
      * write the loaded set of parking and charging options to an instance parking file
      *
      * @param stallSearch the search tree of available parking options
      * @param stalls the stored ParkingZones
      * @param writeDestinationPath a file path to write to
      */
    def toFile(
      stallSearch: StallSearch,
      stalls: Array[ParkingZone],
      writeDestinationPath: String
    ): Unit = {

      val header: String = "taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor"

      Try {
        val destinationFile = new File(writeDestinationPath)
        destinationFile.getParentFile.mkdirs()

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
          val chargingPoint = parkingZone.chargingPoint match {
            case None     => ""
            case Some(cp) => s"$cp"
          }

          s"$tazId,$parkingType,$pricingModel,$chargingPoint,${parkingZone.maxStalls},$feeInCents,"
        }
      } match {
        case Failure(e) =>
        // throw?
        case Success(rows) =>
          // TODO: printwriter or something here
          val newlineFormattedCSVOutput: String = (List(header) ::: rows).mkString("\n")
      }
    }

    /**
      * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
      *
      * @param filePath location in FS of taz parking data file (.csv) with a header row
      * @return table and tree
      */
    def fromFile(filePath: String, dropHeader: Boolean = true): (Array[ParkingZone], StallSearch) = {
      Try {
        scala.io.Source.fromFile(filePath).getLines
      } match {
        case Success(fileContents) =>
          val rows = if (dropHeader) fileContents.drop(1) else fileContents
          fromStream(rows)
        case Failure(e) =>
          throw new java.io.IOException(s"Unable to load file $filePath.\n$e")
      }

    }

    /**
      * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
      *
      * @param csvFileContents each line from a file to be read
      * @return table and tree
      */
    def fromStream(csvFileContents: Iterator[String]): (Array[ParkingZone], StallSearch) = {
      val ParkingFileRowRegex = "^(\\w+),(\\w+),(\\w+),(\\w+),(\\d+),(\\d+),(\\w+)$".r

      // we are building the Array of ParkingZones and a search tree
      val accumulator = (Array.empty[ParkingZone], Map.empty[Id[TAZ], Map[ParkingType, List[Int]]]: StallSearch)

      csvFileContents.foldLeft(accumulator) { (accumulator, csvRow) =>
        csvRow match {
          case ParkingFileRowRegex(
              tazString,
              parkingTypeString,
              pricingModelString,
              chargingTypeString,
              numStallsString,
              feeInCentsString,
              reservedForString
              ) =>
            Try {

              val (stallTable, searchTree) = accumulator

              // parse this row from the source file
              val taz = Id.create(tazString.toUpperCase, classOf[TAZ])
              val parkingType = ParkingType(parkingTypeString)
              val pricingModel = PricingModel(pricingModelString, feeInCentsString)
              val chargingPoint = Try {
                ChargingPoint(chargingTypeString)
              } match {
                case Success(point) => Some(point)
                case Failure(_)     => None
              }
              val numStalls = numStallsString.toInt

              addStallToSearch(taz, parkingType, pricingModel, chargingPoint, numStalls, searchTree, stallTable)

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
    }

    /**
      * a kind of lens-based update for the search tree
      *
      * @param tazId TAZ where this ParkingZone exists
      * @param parkingType a parking type category we are adding/updating
      * @param pricingModel an optional pricing model for the added ParkingZone
      * @param chargingType a optional charging type for the added ParkingZone
      * @param numStalls number of stalls this ParkingZone will start with
      * @param tree the search tree we will update
      * @param stalls the collection of stall data we will update
      * @return updated tree, stalls
      */
    def addStallToSearch(
      tazId: Id[TAZ],
      parkingType: ParkingType,
      pricingModel: Option[PricingModel],
      chargingType: Option[ChargingPoint],
      numStalls: Int,
      tree: StallSearch,
      stalls: Array[ParkingZone]
    ): (Array[ParkingZone], StallSearch) = {

      // find any data stored already within this TAZ and with this ParkingType
      val parkingTypes = tree.getOrElse(tazId, Map())
      val parkingZoneIds: List[Int] = parkingTypes.getOrElse(parkingType, List.empty[Int])

      // create new ParkingZone in array with new parkingZoneId. should this be an ArrayBuilder?
      val parkingZoneId = stalls.length
      val updatedStalls = stalls :+ ParkingZone(numStalls, chargingType, pricingModel)

      // update the tree with the id of this ParkingZone
      val updatedTree =
        tree.updated(tazId, parkingTypes.updated(parkingType, parkingZoneIds :+ parkingZoneId))

      (updatedStalls, updatedTree)
    }
  }
}
