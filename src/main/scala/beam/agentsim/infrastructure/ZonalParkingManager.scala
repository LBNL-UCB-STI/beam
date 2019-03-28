package beam.agentsim.infrastructure

import java.io.{File, FileWriter}
import java.nio.file.{Files, Paths}
import java.util

import akka.actor.{ActorLogging, ActorRef, Props}
import beam.agentsim.Resource._
import beam.agentsim.infrastructure.ParkingManager._
import beam.agentsim.infrastructure.ParkingStall._
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.infrastructure.ZonalParkingManager.ParkingAlternative
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.{BeamServices, HasServices}
import beam.utils.FileUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.supercsv.cellprocessor.constraint.NotNull
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io.{CsvMapReader, CsvMapWriter, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.Random

class ZonalParkingManager(
  override val beamServices: BeamServices,
  val beamRouter: ActorRef,
  parkingStockAttributes: ParkingStockAttributes
) extends ParkingManager(parkingStockAttributes)
    with HasServices
    with ActorLogging {
  val stalls: mutable.Map[Id[ParkingStall], ParkingStall] = mutable.Map()
  val pooledResources: mutable.Map[StallAttributes, StallValues] = mutable.Map()
  var totalStallsInUse = 0
  var stallNum = 0
  val rand = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)

  val pathResourceCSV: String = beamServices.beamConfig.beam.agentsim.taz.parkingFilePath

  val defaultStallAttributes = StallAttributes(
    Id.create("NA", classOf[TAZ]),
    NoOtherExists,
    FlatFee,
    NoCharger,
    ParkingStall.Any
  )
  def defaultStallValues: StallValues = StallValues(Int.MaxValue, 0)
  def defaultRideHailStallValues: StallValues = StallValues(0, 0)

  val depotStallLocationType: DepotStallLocationType =
    beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelLocationType match {
      case "AtRequestLocation" =>
        AtRequestLocation
      case "AtTAZCenter" =>
        AtTAZCenter
      case _ =>
        AtRequestLocation
    }

  def fillInDefaultPooledResources(): Unit = {
    // First do general parking and charging for personal vehicles
    for {
      taz          <- beamServices.tazTreeMap.tazQuadTree.values().asScala
      parkingType  <- List(Residential, Workplace, Public)
      pricingModel <- List(FlatFee, Block)
      chargingType <- List(NoCharger, Level1, Level2, DCFast, UltraFast)
      reservedFor  <- List(ParkingStall.Any)
    } yield {
      val attrib = StallAttributes(taz.tazId, parkingType, pricingModel, chargingType, reservedFor)
      addToPoolResource(attrib, defaultStallValues)
    }
    // Now do parking/charging for ride hail fleet
    for {
      taz          <- beamServices.tazTreeMap.tazQuadTree.values().asScala
      parkingType  <- List(Workplace)
      pricingModel <- List(FlatFee)
      chargingType <- List(Level2, DCFast, UltraFast)
      reservedFor  <- List(ParkingStall.RideHailManager)
    } yield {
      val attrib = StallAttributes(taz.tazId, parkingType, pricingModel, chargingType, reservedFor)
      addToPoolResource(attrib, defaultRideHailStallValues)
    }
  }

  def addToPoolResource(key: StallAttributes, value: StallValues): Unit = {
    pooledResources.put(key, value)
  }

  def updatePooledResources(): Unit = {
    if (Files.exists(Paths.get(beamServices.beamConfig.beam.agentsim.taz.parkingFilePath))) {
      readCsvFile(pathResourceCSV).foreach(f => {
        pooledResources.update(f._1, f._2)
      })
    } else {
      //Used to generate csv file
      parkingStallToCsv(pooledResources, pathResourceCSV) // use to generate initial csv from above data
    }
    // Make a very big pool of NA stalls used to return to agents when there are no alternatives left
    addToPoolResource(defaultStallAttributes, defaultStallValues)
  }

  fillInDefaultPooledResources()
  updatePooledResources()

  val indexer: IndexerForZonalParkingManager = new IndexerForZonalParkingManager(pooledResources.toMap)

  log.info("Zonal Parking Manager loaded with {} total stalls", pooledResources.map(_._2._numStalls).sum)

  override def receive: Receive = {
    case ReleaseParkingStall(stallId) =>
      if (stalls.contains(stallId)) {
        val stall = stalls(stallId)
        val stallValues = pooledResources(stall.attributes)
        stallValues._numStalls += 1
        totalStallsInUse -= 1

        stalls.remove(stall.id)
        if (log.isDebugEnabled) {
          log.debug("CheckInResource with {} available stalls ", getAvailableStalls)
        }
      }

    case inquiry: DepotParkingInquiry =>
      if (log.isDebugEnabled) {
        log.debug("DepotParkingInquiry with {} available stalls ", getAvailableStalls)
      }
      val tAZsWithDists = findTAZsWithinDistance(inquiry.customerLocationUtm, 10000.0, 20000.0)
      val maybeFoundStalls = indexer.filter(tAZsWithDists, inquiry.reservedFor)

      val maybeParkingAttributes = maybeFoundStalls.flatMap {
        _.keys.toVector
          .sortBy { attrs =>
            ChargingType.getChargerPowerInKW(attrs.chargingType)
          }
          .reverse
          .headOption
      }
      val maybeParkingStall = maybeParkingAttributes.flatMap { attrib =>
        // Location is either TAZ center or random withing 5km of driver location
        val newLocation = depotStallLocationType match {
          case AtTAZCenter if beamServices.tazTreeMap.getTAZ(attrib.tazId).isDefined =>
            beamServices.tazTreeMap.getTAZ(attrib.tazId).get.coord
          case _ =>
            inquiry.customerLocationUtm
        }
        maybeCreateNewStall(attrib, newLocation, 0.0, maybeFoundStalls.get.get(attrib))
      }

      maybeParkingStall.foreach { stall =>
        stalls.put(stall.id, stall)
        val stallValues = pooledResources(stall.attributes)
        totalStallsInUse += 1
        stallValues._numStalls -= 1
      }
      if (log.isDebugEnabled) {
        log.debug("DepotParkingInquiry reserved stall: {}", maybeParkingStall)
        log.debug("DepotParkingInquiry {} available stalls ", getAvailableStalls)
      }

      val response = DepotParkingInquiryResponse(maybeParkingStall, inquiry.requestId)
      sender() ! response

    case inquiry: ParkingInquiry =>
      val nearbyTAZsWithDistances = findTAZsWithinDistance(inquiry.destinationUtm, 500.0, 16000.0)
      val preferredType = inquiry.activityType match {
        case act if act.equalsIgnoreCase("home") => Residential
        case act if act.equalsIgnoreCase("work") => Workplace
        case _                                   => Public
      }

      /*
       * To save time avoiding route calculations, we look for the trivial case: nearest TAZ with activity type matching available parking type.
       */
      val maybeFoundStall = nearbyTAZsWithDistances.size match {
        case 0 =>
          None
        case _ =>
          val tazId = nearbyTAZsWithDistances.head._1.tazId
          indexer.find(tazId, preferredType, inquiry.reservedFor).headOption
      }
      val maybeDominantSpot = maybeFoundStall match {
        case Some((idx, stallValue)) if inquiry.chargingPreference == NoNeed =>
          maybeCreateNewStall(
            StallAttributes(
              nearbyTAZsWithDistances.head._1.tazId,
              preferredType,
              idx.pricingModel,
              NoCharger,
              inquiry.reservedFor
            ),
            inquiry.destinationUtm,
            0.0,
            Some(stallValue.copy()) // let's send a copy to be in safe
          )
        case _ =>
          None
      }

      respondWithStall(
        maybeDominantSpot match {
          case Some(stall) =>
            stall
          case None =>
            inquiry.chargingPreference match {
              case NoNeed =>
                selectPublicStall(inquiry, 500.0)
              case _ =>
                selectStallWithCharger(inquiry, 500.0)
            }
        },
        inquiry.requestId,
        inquiry.reserveStall
      )
  }

  private def maybeCreateNewStall(
    attrib: StallAttributes,
    atLocation: Location,
    withCost: Double,
    stallValues: Option[StallValues],
    reservedFor: ReservedParkingType = ParkingStall.Any
  ): Option[ParkingStall] = {
    if (pooledResources(attrib).numStalls > 0) {
      stallNum = stallNum + 1
      Some(
        new ParkingStall(
          Id.create(stallNum, classOf[ParkingStall]),
          attrib,
          atLocation,
          withCost,
          stallValues
        )
      )
    } else {
      None
    }
  }

  def respondWithStall(stall: ParkingStall, requestId: Int, reserveStall: Boolean): Unit = {
    if (reserveStall) {
      stalls.put(stall.id, stall)
      val stallValues = pooledResources(stall.attributes)
      totalStallsInUse += 1
      if (totalStallsInUse % 1000 == 0) log.debug(s"Parking stalls in use: {}", totalStallsInUse)
      stallValues._numStalls -= 1
    }
    sender() ! ParkingInquiryResponse(stall, requestId)
  }

  // TODO make these distributions more custom to the TAZ and stall type
  def sampleLocationForStall(taz: TAZ, attrib: StallAttributes): Location = {
    val radius = math.sqrt(taz.areaInSquareMeters) / 2
    val lambda = 0.01
    val deltaRadiusX = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda
    val deltaRadiusY = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda

    val x = taz.coord.getX + deltaRadiusX
    val y = taz.coord.getY + deltaRadiusY
    new Location(x, y)
    //new Location(taz.coord.getX + rand.nextDouble() * 500.0 - 250.0, taz.coord.getY + rand.nextDouble() * 500.0 - 250.0)
  }

  // TODO make pricing into parameters
  // TODO make Block parking model based off a schedule
  def calculateCost(
    attrib: StallAttributes,
    feeInCents: Int,
    arrivalTime: Long,
    parkingDuration: Double
  ): Double = {
    attrib.pricingModel match {
      case FlatFee => feeInCents.toDouble / 100.0
      case Block   => parkingDuration / 3600.0 * (feeInCents.toDouble / 100.0)
    }
  }

  def selectPublicStall(inquiry: ParkingInquiry, startSearchRadius: Double): ParkingStall = {
    val nearbyTAZsWithDistances =
      findTAZsWithinDistance(inquiry.destinationUtm, startSearchRadius, ZonalParkingManager.maxSearchRadius)
    val allOptions: Vector[ParkingAlternative] = nearbyTAZsWithDistances.flatMap { taz =>
      val found = indexer.find(taz._1.tazId, Public, ParkingStall.Any)
      val foundAfter = found.map {
        case (indexForFind, stallValues) =>
          val attrib =
            StallAttributes(indexForFind.tazId, indexForFind.parkingType, indexForFind.pricingModel, NoCharger, Any)
          val stallLoc = sampleLocationForStall(taz._1, attrib)
          val walkingDistance = beamServices.geo.distUTMInMeters(stallLoc, inquiry.destinationUtm)
          val valueOfTimeSpentWalking = walkingDistance / 1.4 / 3600.0 * inquiry.attributesOfIndividual.valueOfTime // 1.4 m/s avg. walk
          val cost = calculateCost(
            attrib,
            stallValues.feeInCents,
            inquiry.arrivalTime,
            inquiry.parkingDuration
          )
          ParkingAlternative(attrib, stallLoc, cost, cost + valueOfTimeSpentWalking, stallValues)
      }.toVector
      foundAfter
    }
    val chosenStall = allOptions.sortBy(_.rankingWeight).headOption match {
      case Some(alternative) =>
        maybeCreateNewStall(
          alternative.stallAttributes,
          alternative.location,
          alternative.cost,
          Some(alternative.stallValues)
        )
      case None => None
    }
    // Finally, if no stall found, repeat with larger search distance for TAZs or create one very expensive
    chosenStall match {
      case Some(stall) => stall
      case None =>
        if (startSearchRadius * 2.0 > ZonalParkingManager.maxSearchRadius) {
//          log.error("No stall found for inquiry: {}",inquiry)
          stallNum = stallNum + 1
          new ParkingStall(
            Id.create(stallNum, classOf[ParkingStall]),
            defaultStallAttributes,
            inquiry.destinationUtm,
            1000.0,
            Some(defaultStallValues)
          )
        } else {
          selectPublicStall(inquiry, startSearchRadius * 2.0)
        }
    }
  }

  def findTAZsWithinDistance(searchCenter: Location, startRadius: Double, maxRadius: Double): Vector[(TAZ, Double)] = {
    var nearbyTAZs: Vector[TAZ] = Vector()
    var searchRadius = startRadius
    while (nearbyTAZs.isEmpty && searchRadius <= maxRadius) {
      nearbyTAZs = beamServices.tazTreeMap.tazQuadTree
        .getDisk(searchCenter.getX, searchCenter.getY, searchRadius)
        .asScala
        .toVector
      searchRadius = searchRadius * 2.0
    }
    nearbyTAZs
      .zip(nearbyTAZs.map { taz =>
        // Note, this assumes both TAZs and SearchCenter are in local coordinates, and therefore in units of meters
        GeoUtils.distFormula(taz.coord, searchCenter)
      })
      .sortBy(_._2)
  }

  def selectStallWithCharger(inquiry: ParkingInquiry, startRadius: Double): ParkingStall = ???

  def readCsvFile(filePath: String): mutable.Map[StallAttributes, StallValues] = {
    val res: mutable.Map[StallAttributes, StallValues] = mutable.Map()

    FileUtils.using(
      new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
    ) { mapReader =>
      val header = mapReader.getHeader(true)
      var line: java.util.Map[String, String] = mapReader.read(header: _*)
      while (null != line) {

        val taz = Id.create(line.get("taz").toUpperCase, classOf[TAZ])
        val parkingType = ParkingType.fromString(line.get("parkingType"))
        val pricingModel = PricingModel.fromString(line.get("pricingModel"))
        val chargingType = ChargingType.fromString(line.get("chargingType"))
        val numStalls = line.get("numStalls").toInt
        //        val parkingId = line.get("parkingId")
        val feeInCents = line.get("feeInCents").toInt
        val reservedForString = line.get("reservedFor")
        val reservedFor = getReservedFor(reservedForString)

        res.put(
          StallAttributes(taz, parkingType, pricingModel, chargingType, reservedFor),
          StallValues(numStalls, feeInCents)
        )

        line = mapReader.read(header: _*)
      }
    }
    res
  }

  def getReservedFor(reservedFor: String): ReservedParkingType = {
    reservedFor match {
      case "RideHailManager" => ParkingStall.RideHailManager
      case _                 => ParkingStall.Any
    }
  }

  def parkingStallToCsv(
    pooledResources: mutable.Map[ParkingStall.StallAttributes, StallValues],
    writeDestinationPath: String
  ): Unit = {
    var mapWriter: ICsvMapWriter = null
    try {
      val destinationFile = new File(writeDestinationPath)
      destinationFile.getParentFile.mkdirs()
      mapWriter = new CsvMapWriter(new FileWriter(destinationFile), CsvPreference.STANDARD_PREFERENCE)

      val header = Array[String](
        "taz",
        "parkingType",
        "pricingModel",
        "chargingType",
        "numStalls",
        "feeInCents",
        "reservedFor"
      ) //, "parkingId"
      val processors = Array[CellProcessor](
        new NotNull(), // Id (must be unique)
        new NotNull(),
        new NotNull(),
        new NotNull(),
        new NotNull(),
        new NotNull(),
        new NotNull()
      ) //new UniqueHashCode()
      mapWriter.writeHeader(header: _*)

      val range = 1 to pooledResources.size
      val resourcesWithId = (pooledResources zip range).toSeq
        .sortBy(_._2)

      for (((attrs, values), _) <- resourcesWithId) {
        val tazToWrite = new util.HashMap[String, Object]()
        tazToWrite.put(header(0), attrs.tazId)
        tazToWrite.put(header(1), attrs.parkingType.toString)
        tazToWrite.put(header(2), attrs.pricingModel.toString)
        tazToWrite.put(header(3), attrs.chargingType.toString)
        tazToWrite.put(header(4), "" + values.numStalls)
        tazToWrite.put(header(5), "" + values.feeInCents)
        tazToWrite.put(header(6), "" + attrs.reservedFor.toString)
        //        tazToWrite.put(header(6), "" + values.parkingId.getOrElse(Id.create(id, classOf[StallValues])))
        mapWriter.write(tazToWrite, header, processors)
      }
    } finally {
      if (mapWriter != null) {
        mapWriter.close()
      }
    }
  }

  private def getAvailableStalls: Long = {
    pooledResources
      .filter(_._1.reservedFor == RideHailManager)
      .map(_._2.numStalls.toLong)
      .sum
  }
}

object ZonalParkingManager {
  case class ParkingAlternative(
    stallAttributes: StallAttributes,
    location: Location,
    cost: Double,
    rankingWeight: Double,
    stallValues: StallValues
  )

  def props(
    beamServices: BeamServices,
    beamRouter: ActorRef,
    parkingStockAttributes: ParkingStockAttributes
  ): Props = {
    Props(new ZonalParkingManager(beamServices, beamRouter, parkingStockAttributes))
  }

  val maxSearchRadius = 10e3
}
