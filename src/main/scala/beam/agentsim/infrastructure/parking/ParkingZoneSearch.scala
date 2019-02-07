package beam.agentsim.infrastructure.parking

import java.io.File

import scala.collection.Map
import scala.util.{Failure, Success, Try}

import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.infrastructure.parking.charging.ChargingInquiryData._
import beam.agentsim.infrastructure.parking.charging._
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
    * look for matching ParkingZones, optionally based on charging infrastructure requirements, within a TAZ
    * @param tazId the TAZ we are looking in
    * @param parkingTypes the parking types we are interested in
    * @param chargingInquiryData ChargingPreference per type of ChargingPoint
    * @param tree search tree of parking infrastructure
    * @param stalls stored ParkingZone data
    * @param costFunction ranking function for comparing options
    * @return list of discovered ParkingZone ids, corresponding to the Array[ParkingZone], ranked by the costFunction
    */
  def findPossibleParkingZones(
    tazId: Id[TAZ],
    parkingTypes: Seq[ParkingType],
    chargingInquiryData: Option[ChargingInquiryData],
    tree: StallSearch,
    stalls: Array[ParkingZone],
    costFunction: (ParkingZone, Option[ChargingPreference]) => Double
  ): Iterable[(Int, Double)] = {


    // conduct search (toList required to combine Option and List monads)
    for {
      parkingTypesSubtree <- tree.get(tazId).toList
      validParkingType    <- parkingTypes
      parkingZoneIds      <- parkingTypesSubtree.get(validParkingType).toList
      parkingZoneId       <- parkingZoneIds
    } yield {

      // get the zone
      val parkingZone = Try {
        stalls(parkingZoneId)
      } match {
        case Success(zone) => zone
        case Failure(e) =>
          // throw? or fail silently? ...
        throw new IndexOutOfBoundsException(s"Attempting to access ParkingZone with index $parkingZoneId failed.\n$e")
      }

      // evaluate cost without/with a charging preference
      chargingInquiryData match {

        case None =>

          (parkingZoneId, costFunction(parkingZone, None))

        case Some(chargingData) =>

          val pref: Option[ChargingPreference] = for {
            chargingPoint       <- parkingZone.chargingPoint
            chargingPreference <- chargingData.data.get(chargingPoint)
          } yield chargingPreference

          (parkingZoneId, costFunction(parkingZone, pref))
      }
    }
  }



  /**
    * loads taz parking data from file, creating a lookup table of stalls along with a search tree to find stalls
    * @param filePath location in FS of taz parking data file (.csv)
    * @return table and tree
    */
  def readCsvFile(filePath: String): (Array[ParkingZone], StallSearch) = {

    val ParkingFileRowRegex = "^(\\d+),(\\w+),(\\w+),(\\w+),(\\d+),(\\d+),(\\w+)$".r

    // we are building the Array of ParkingZones and a search tree
    val accumulator = (Array.empty[ParkingZone], Map.empty[Id[TAZ], Map[ParkingType, List[Int]]] : StallSearch)

    // read the file in and, with each row, build both our search tree and our lookup table
    scala.io.Source.
      fromFile(filePath).
      getLines.
      foldLeft(accumulator) { (accumulator, csvRow) =>
        csvRow match {
          case ParkingFileRowRegex(tazString, parkingTypeString, pricingModelString, chargingTypeString, numStallsString, feeInCentsString, reservedForString) =>
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
                case Failure(_) => None
              }
              val numStalls = numStallsString.toInt

              addStallToSearch(taz, parkingType, pricingModel, chargingPoint, numStalls, searchTree, stallTable)

            } match {
              case Success(updatedAccumulator) => updatedAccumulator

              case Failure(e) =>
                // allow the construction of the ZonalParkingManager to fail with this exception
                // re-casting this error to mention the
                throw new java.io.IOException(s"Failed to load parking data from file $filePath.\n$e")
            }
          case _ =>
            // didn't match regex (for example, the header, or didn't have the correct # of columns, or non-numeric where it should be) - make no changes
            accumulator
        }
      }
  }



  /**
    * a kind of lens-based update for the search tree
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
    tazId           : Id[TAZ],
    parkingType     : ParkingType,
    pricingModel    : Option[PricingModel],
    chargingType    : Option[ChargingPoint],
    numStalls       : Int,
    tree            : StallSearch,
    stalls: Array[ParkingZone]
  ): (Array[ParkingZone], StallSearch)= {

    // find any data stored already within this TAZ and with this ParkingType
    val parkingTypes = tree.getOrElse(tazId, Map())
    val parkingZoneIds: List[Int]  = parkingTypes.getOrElse(parkingType, List.empty[Int])

    // create new ParkingZone in array with new parkingZoneId. should this be an ArrayBuilder?
    val parkingZoneId = stalls.length
    val updatedStalls = stalls :+ ParkingZone(numStalls, chargingType, pricingModel)

    // update the tree with the id of this ParkingZone
    val updatedTree =
      tree.updated(tazId,
        parkingTypes.updated(parkingType,
          parkingZoneIds :+ parkingZoneId
        )
      )

    (updatedStalls, updatedTree)
  }


  /**
    * write the loaded set of parking and charging options to an instance parking file
    * @param stallSearch the search tree of available parking options
    * @param stalls the stored ParkingZones
    * @param writeDestinationPath a file path to write to
    */
  def parkingStallToCsv(
    stallSearch: StallSearch,
    stalls: Array[ParkingZone],
    writeDestinationPath: String
  ): Unit = {

    val header: String = "taz,parkingType,pricingModel,chargingType,numStalls,feeInCents,reservedFor"

    Try {
      val destinationFile = new File(writeDestinationPath)
      destinationFile.getParentFile.mkdirs()

      for {
        (tazId, parkingTypesSubtree) <- stallSearch.toList
        (parkingType, parkingZoneIds) <- parkingTypesSubtree.toList
        parkingZoneId <- parkingZoneIds
      } yield {

        val parkingZone = stalls(parkingZoneId)
        val (pricingModel, feeInCents)= parkingZone.pricingModel match {
          case None => ("", "")
          case Some(pm) => (s"$pm", s"${pm.cost}")
        }
        val chargingPoint = parkingZone.chargingPoint match {
          case None => ""
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
}