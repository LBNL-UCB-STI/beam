package beam.agentsim.infrastructure

import java.io.FileWriter
import java.nio.file.{Files, Paths}
import java.util

import akka.actor.{ActorRef, Props}
import beam.agentsim.Resource._
import beam.agentsim.agents.PersonAgent
import beam.agentsim.events.SpaceTime
import beam.agentsim.infrastructure.ParkingManager._
import beam.agentsim.infrastructure.ParkingStall._
import beam.agentsim.infrastructure.TAZTreeMap.TAZ
import beam.agentsim.infrastructure.ZonalParkingManager.ParkingAlternative
import beam.router.BeamRouter.Location
import beam.sim.common.GeoUtils
import beam.sim.{BeamServices, HasServices}
import beam.utils.FileUtils
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.vehicles.Vehicle
import org.supercsv.cellprocessor.ift.CellProcessor
import org.supercsv.io.{CsvMapReader, CsvMapWriter, ICsvMapReader, ICsvMapWriter}
import org.supercsv.prefs.CsvPreference
import org.supercsv.cellprocessor.constraint.NotNull

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.util.Random

class ZonalParkingManager(
  override val beamServices: BeamServices,
  val beamRouter: ActorRef,
  parkingStockAttributes: ParkingStockAttributes
) extends ParkingManager(parkingStockAttributes)
    with HasServices {
  override val resources: mutable.Map[Id[ParkingStall], ParkingStall] =
    collection.mutable.Map[Id[ParkingStall], ParkingStall]()
  val pooledResources: mutable.Map[StallAttributes, StallValues] = mutable.Map()
  var stallnum = 0

  val pathResourceCSV: String = beamServices.beamConfig.beam.agentsim.taz.parking

  val defaultStallAtrrs = StallAttributes(
    Id.create("NA", classOf[TAZ]),
    NoOtherExists,
    FlatFee,
    NoCharger,
    ParkingStall.Any
  )
  val defaultStallValues = StallValues(Int.MaxValue, 0)

  val depotStallLocationType: DepotStallLocationType = beamServices.beamConfig.beam.agentsim.agents.rideHail.refuelLocationType match {
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
      pooledResources.put(
        StallAttributes(taz.tazId, parkingType, pricingModel, chargingType, reservedFor),
        defaultStallValues
      )
    }
    // Now do parking/charging for ride hail fleet
    for {
      taz          <- beamServices.tazTreeMap.tazQuadTree.values().asScala
      parkingType  <- List(Workplace)
      pricingModel <- List(FlatFee)
      chargingType <- List(Level2, DCFast, UltraFast)
      reservedFor  <- List(ParkingStall.RideHailManager)
    } yield {
      pooledResources.put(
        StallAttributes(taz.tazId, parkingType, pricingModel, chargingType, reservedFor),
        defaultStallValues
      )
    }
  }

  def updatePooledResources(): Unit = {
    if (Files.exists(Paths.get(beamServices.beamConfig.beam.agentsim.taz.parking))) {
      readCsvFile(pathResourceCSV).foreach(f => {
        pooledResources.update(f._1, f._2)
      })
    } else {
      //Used to generate csv file
      parkingStallToCsv(pooledResources, pathResourceCSV) // use to generate initial csv from above data
    }
    // Make a very big pool of NA stalls used to return to agents when there are no alternatives left
    pooledResources.put(defaultStallAtrrs, defaultStallValues)
  }

  fillInDefaultPooledResources()
  updatePooledResources()

  override def receive: Receive = {
    case RegisterResource(stallId: Id[ParkingStall]) =>
    // For Zonal Parking, stalls are created internally

    case NotifyResourceInUse(stallId: Id[ParkingStall], whenWhere) =>
    // Irrelevant for parking

    case CheckInResource(stallId: Id[ParkingStall], availableIn: Option[SpaceTime]) =>
      val stall = resources(stallId)
      val stallValues = pooledResources(stall.attributes)

      pooledResources.update(
        stall.attributes,
        stallValues.copy(numStalls = stallValues.numStalls + 1)
      )
      resources.remove(stall.id)

    case CheckOutResource(_) =>
      // Because the ZonalParkingManager is in charge of deciding which stalls to assign, this should never be received
      throw new RuntimeException(
        "Illegal use of CheckOutResource, ZonalParkingManager is responsible for checking out stalls in fleet."
      )

    case inquiry @ DepotParkingInquiry(vehicleId: Id[Vehicle], location: Location, reservedFor: ReservedParkingType) =>
      val tazsWithDists = findTAZsWithDistances(location, 1000.0)
      val maybeFoundStalls = tazsWithDists
        .find {
          case (taz, _) =>
            pooledResources.find {
              case (attr, values) =>
                attr.tazId.equals(taz.tazId) &&
                attr.reservedFor.equals(reservedFor) &&
                values.numStalls > 0
            }.isDefined
        }
        .map {
          case (taz, _) =>
            pooledResources.filter {
              case (attr, values) =>
                attr.tazId.equals(taz.tazId) &&
                attr.reservedFor.equals(reservedFor) &&
                values.numStalls > 0
            }
        }

      val maybeParkingAttribs = maybeFoundStalls.flatMap {
        _.keys.toVector
          .sortBy { attribs =>
            ChargingType.getChargerPowerInKW(attribs.chargingType)
          }
          .reverse
          .headOption
      }
      val maybeParkingStall = maybeParkingAttribs.flatMap{attrib =>
        // Location is either TAZ center or random withing 5km of driver location
        val newLocation = depotStallLocationType match {
          case AtTAZCenter if beamServices.tazTreeMap.getTAZ(attrib.tazId).isDefined =>
             beamServices.tazTreeMap.getTAZ(attrib.tazId).get.coord
          case _ =>
            location
        }
        maybeCreateNewStall(attrib, newLocation, 0.0, maybeFoundStalls.get.get(attrib))
      }

      maybeParkingStall.foreach { stall =>
        resources.put(stall.id, stall)
        val stallValues = pooledResources(stall.attributes)
        pooledResources.update(
          stall.attributes,
          stallValues.copy(numStalls = stallValues.numStalls - 1)
        )
      }

      val response = DepotParkingInquiryResponse(maybeParkingStall, inquiry.requestId)
      sender() ! response

    case inquiry @ ParkingInquiry(
          customerId: Id[PersonAgent],
          customerLocationUtm: Location,
          destinationUtm: Location,
          activityType: String,
          valueOfTime: Double,
          chargingPreference: ChargingPreference,
          arrivalTime: Long,
          parkingDuration: Double,
          reservedFor: ReservedParkingType
        ) =>
      val nearbyTazsWithDistances = findTAZsWithDistances(destinationUtm, 500.0)
      val preferredType = activityType match {
        case act if act.equalsIgnoreCase("home") => Residential
        case act if act.equalsIgnoreCase("work") => Workplace
        case _                                   => Public
      }

      if (inquiry.parkingDuration > 0) {
        val jjj = 0
      }

      /*
       * To save time avoiding route calculations, we look for the trivial case: nearest TAZ with activity type matching available parking type.
       */
      val maybeFoundStall = pooledResources.find {
        case (attr, values) =>
          attr.tazId.equals(nearbyTazsWithDistances.head._1.tazId) &&
          attr.parkingType == preferredType &&
          attr.reservedFor.equals(reservedFor) &&
          values.numStalls > 0 &&
          values.feeInCents == 0
      }
      val maybeDominantSpot = maybeFoundStall match {
        case Some(foundStall) if chargingPreference == NoNeed =>
          maybeCreateNewStall(
            StallAttributes(
              nearbyTazsWithDistances.head._1.tazId,
              preferredType,
              foundStall._1.pricingModel,
              NoCharger,
              reservedFor
            ),
            destinationUtm,
            0.0,
            Some(foundStall._2)
          )
        case _ =>
          None
      }

      respondWithStall(
        maybeDominantSpot match {
          case Some(stall) =>
            stall
          case None =>
            chargingPreference match {
              case NoNeed =>
                selectPublicStall(inquiry, 500.0)
              case _ =>
                selectStallWithCharger(inquiry, 500.0)
            }
        },
        inquiry.requestId
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
      stallnum = stallnum + 1
      Some(
        new ParkingStall(
          Id.create(stallnum, classOf[ParkingStall]),
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

  def respondWithStall(stall: ParkingStall, requestId: Int): Unit = {
    resources.put(stall.id, stall)
    val stallValues = pooledResources(stall.attributes)
    pooledResources.update(
      stall.attributes,
      stallValues.copy(numStalls = stallValues.numStalls - 1)
    )
    sender() ! ParkingInquiryResponse(stall, requestId)
  }

  // TODO make these distributions more custom to the TAZ and stall type
  def sampleLocationForStall(taz: TAZ, attrib: StallAttributes): Location = {
    val rand = new Random()
    val radius = math.sqrt(taz.area) / 2
    val lambda = 1
    val deltaRadius = -math.log(1 - (1 - math.exp(-lambda * radius)) * rand.nextDouble()) / lambda

    val x = taz.coord.getX + deltaRadius
    val y = taz.coord.getY + deltaRadius
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

  def selectPublicStall(inquiry: ParkingInquiry, searchRadius: Double): ParkingStall = {
    val nearbyTazsWithDistances = findTAZsWithDistances(inquiry.destinationUtm, searchRadius)
    val allOptions: Vector[ParkingAlternative] = nearbyTazsWithDistances.flatMap { taz =>
      Vector(FlatFee, Block).flatMap { pricingModel =>
        val attrib =
          StallAttributes(taz._1.tazId, Public, pricingModel, NoCharger, ParkingStall.Any)
        val stallValues = pooledResources(attrib)
        if (stallValues.numStalls > 0) {
          val stallLoc = sampleLocationForStall(taz._1, attrib)
          val walkingDistance = beamServices.geo.distInMeters(stallLoc, inquiry.destinationUtm)
          val valueOfTimeSpentWalking = walkingDistance / 1.4 / 3600.0 * inquiry.valueOfTime // 1.4 m/s avg. walk
          val cost = calculateCost(
            attrib,
            stallValues.feeInCents,
            inquiry.arrivalTime,
            inquiry.parkingDuration
          )
          Vector(
            ParkingAlternative(attrib, stallLoc, cost, cost + valueOfTimeSpentWalking, stallValues)
          )
        } else {
          Vector[ParkingAlternative]()
        }
      }
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
        if (searchRadius * 2.0 > ZonalParkingManager.maxSearchRadius) {
          stallnum = stallnum + 1
          new ParkingStall(
            Id.create(stallnum, classOf[ParkingStall]),
            defaultStallAtrrs,
            inquiry.destinationUtm,
            1000.0,
            Some(defaultStallValues)
          )
        } else {
          selectPublicStall(inquiry, searchRadius * 2.0)
        }
    }
  }

  def findTAZsWithDistances(searchCenter: Location, startRadius: Double): Vector[(TAZ, Double)] = {
    var nearbyTazs: Vector[TAZ] = Vector()
    var searchRadius = startRadius
    while (nearbyTazs.isEmpty) {
      if (searchRadius > ZonalParkingManager.maxSearchRadius) {
        throw new RuntimeException(
          "Parking search radius has reached 10,000 km and found no TAZs, possible map projection error?"
        )
      }
      nearbyTazs = beamServices.tazTreeMap.tazQuadTree
        .getDisk(searchCenter.getX, searchCenter.getY, searchRadius)
        .asScala
        .toVector
      searchRadius = searchRadius * 2.0
    }
    nearbyTazs
      .zip(nearbyTazs.map { taz =>
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
      mapWriter = new CsvMapWriter(new FileWriter(writeDestinationPath), CsvPreference.STANDARD_PREFERENCE)

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

      for (((attrs, values), id) <- resourcesWithId) {
        val tazToWrite = new util.HashMap[String, Object]();
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

  val maxSearchRadius = 10e6
}
