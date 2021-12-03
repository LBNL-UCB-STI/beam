package beam.utils.data.synthpop

import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.{PointGenerator, RandomPointsInGridGenerator}
import beam.utils.ProfilingUtils
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.ctpp.readers.flow.TimeLeavingHomeTableReader
import beam.utils.data.synthpop.generators.{RandomWorkDestinationGenerator, WorkedDurationGeneratorImpl}
import beam.utils.data.synthpop.models.Models
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, Gender, PowPumaGeoId, PumaGeoId}
import beam.utils.scenario._
import beam.utils.scenario.generic.readers.{CsvHouseholdInfoReader, CsvPersonInfoReader, CsvPlanElementReader}
import beam.utils.scenario.generic.writers.{CsvHouseholdInfoWriter, CsvPersonInfoWriter, CsvPlanElementWriter}
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.{Envelope, Geometry}
import org.apache.commons.math3.random.MersenneTwister
import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.util.Try

case class PersonWithExtraInfoPuma(person: Models.Person, workDest: PowPumaGeoId, timeLeavingHomeRange: Range)

class PumaLevelScenarioGenerator(
  val pathToHouseholdFile: String,
  val pathToPopulationFile: String,
  val dbInfo: CTPPDatabaseInfo,
  val pathToPumaShapeFile: String,
  val pathToPowPumaShapeFile: String,
  val pathToBlockGroupShapeFile: String,
  val pathToCongestionLevelDataFile: String,
  val pathToWorkedHours: String,
  val pathToOsmMap: String,
  val randomSeed: Int,
  val offPeakSpeedMetersPerSecond: Double = 20.5638, // https://inrix.com/scorecard-city/?city=Austin%2C%20TX&index=84
  val defaultValueOfTime: Double = 8.0
) extends ScenarioGenerator
    with StrictLogging {

  logger.info(s"Initializing...")

  private val mapBoundingBox: Envelope = getBoundingBoxOfOsmMap(pathToOsmMap)

  private val rndGen: MersenneTwister = new MersenneTwister(randomSeed) // Random.org

  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    // TODO: Is it truth for all cases? Check the coverage https://epsg.io/26910
    // WGS84 bounds:
    //-172.54 23.81
    //-47.74 86.46
    override def localCRS: String = "epsg:26910"
  }

  private val defaultTimeLeavingHomeRange: Range = Range(6 * 3600, 7 * 3600)

  private val congestionLevelData: CsvCongestionLevelData = new CsvCongestionLevelData(pathToCongestionLevelDataFile)

  private val planElementTemplate: PlanElement = PlanElement(
    tripId = "",
    personId = PersonId("1"),
    planIndex = 0,
    planScore = 0,
    planSelected = true,
    planElementType = "activity",
    planElementIndex = 1,
    activityType = None,
    activityLocationX = None,
    activityLocationY = None,
    activityEndTime = None,
    legMode = None,
    legDepartureTime = None,
    legTravelTime = None,
    legRouteType = None,
    legRouteStartLink = None,
    legRouteEndLink = None,
    legRouteTravelTime = None,
    legRouteDistance = None,
    legRouteLinks = Seq.empty,
    geoId = None
  )

  private val rndWorkDestinationGenerator: RandomWorkDestinationGenerator =
    new RandomWorkDestinationGenerator(dbInfo)

  private val workedDurationGeneratorImpl: WorkedDurationGeneratorImpl =
    new WorkedDurationGeneratorImpl(pathToWorkedHours, new MersenneTwister(randomSeed))

  private val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography =
    ResidenceToWorkplaceFlowGeography.`PUMA5 To POWPUMA`

  private val sourceToTimeLeavingOD =
    new TimeLeavingHomeTableReader(dbInfo, residenceToWorkplaceFlowGeography).read().groupBy(x => x.source)
  private val pointsGenerator: PointGenerator = new RandomPointsInGridGenerator(1.1)

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  private val households: Map[String, Models.Household] =
    new HouseholdReader(pathToHouseholdFile)
      .read()
      .map { hh =>
        val updatedHh = if (hh.income < 0) {
          logger.warn(s"Income for household[${hh.id}] is negative: ${hh.income}, setting it to zero")
          hh.copy(income = 0)
        } else hh
        updatedHh
      }
      .groupBy(x => x.id)
      .map { case (hhId, xs) => hhId -> xs.head }

  private val householdIdToPersons: Map[String, Seq[Models.Person]] =
    new PopulationReader(pathToPopulationFile).read().groupBy(x => x.householdId)

  private val householdWithPersons: Map[Models.Household, Seq[Models.Person]] = householdIdToPersons.map {
    case (hhId, persons) =>
      val household = households(hhId)
      (household, persons)
  }
  logger.info(s"householdWithPersons: ${householdWithPersons.size}")
  private val geoIdToHouseholds = households.values.groupBy(x => x.geoId)
  private val uniqueGeoIds = geoIdToHouseholds.keySet
  logger.info(s"uniqueGeoIds: ${uniqueGeoIds.size}")

  private val uniqueStates = households.map(_._2.geoId.state).toSet
  logger.info(s"uniqueStates: ${uniqueStates.size}")

  private val geoSvc: GeoService = new GeoService(
    GeoServiceInputParam("", pathToBlockGroupShapeFile, pathToOsmMap),
    uniqueGeoIds,
    geoUtils
  )

  val pumaGeoIdToGeom: Map[PumaGeoId, Geometry] = geoSvc.getPumaMap(pathToPumaShapeFile)
  val powPumaGeoIdMap: Map[PowPumaGeoId, Geometry] = geoSvc.getPlaceOfWorkPumaMap(pathToPowPumaShapeFile, uniqueStates)

  val blockGroupToPumaMap: Map[BlockGroupGeoId, PumaGeoId] = ProfilingUtils.timed(
    s"getBlockGroupToPuma for blockGroupGeoIdToGeom ${geoSvc.blockGroupGeoIdToGeom.size} and pumaIdToMap ${pumaGeoIdToGeom.size}",
    x => logger.info(x)
  ) {
    getBlockGroupToPuma
  }
  logger.info(s"blockGroupToPumaMap: ${blockGroupToPumaMap.size}")

  logger.info(s"Initializing finished")

  override def generate: ScenarioResult = {
    var globalPersonId: Int = 0

    val blockGroupGeoIdToHouseholds = getBlockGroupIdToHouseholdAndPeople(blockGroupToPumaMap, geoIdToHouseholds)

    // Build work destination to the number of occurrences
    // We need this to be able to generate random work destinations inside geometry.
    val allWorkingDestinations = blockGroupGeoIdToHouseholds.values.flatMap { x =>
      x.flatMap { case (_, xs) => xs.map(_.workDest) }
    }
    val powPumaToOccurrences = allWorkingDestinations.foldLeft(Map[PowPumaGeoId, Int]()) { case (acc, c) =>
      val occur = acc.getOrElse(c, 0) + 1
      acc.updated(c, occur)
    }
    logger.info(s"allWorkingDestinations: ${allWorkingDestinations.size}")
    logger.info(s"powPumaToOccurrences: ${powPumaToOccurrences.size}")
    powPumaToOccurrences.foreach { case (powPumaGeoId, cnt) =>
      logger.info(s"$powPumaGeoId => $cnt")
    }
    // Generate all work destinations which will be later assigned to people
    val powPumaGeoIdToWorkingLocations = powPumaToOccurrences.par.map {
      case (powPumaGeoId: PowPumaGeoId, nWorkingPlaces) =>
        val workingGeos = powPumaGeoIdMap.get(powPumaGeoId) match {
          case Some(geom) =>
            // FIXME
            val nLocations = nWorkingPlaces // if (nWorkingPlaces > 10000) 10000 else nWorkingPlaces
            ProfilingUtils.timed(s"Generate $nWorkingPlaces geo points in $powPumaGeoId", x => logger.info(x)) {
              pointsGenerator.generate(geom, nLocations)
            }
          case None =>
            logger.warn(s"Can't find $powPumaGeoId in `powPumaGeoIdMap`")
            Seq.empty
        }
        powPumaGeoId -> workingGeos
    }.seq

    val blockGroupGeoIdToHouseholdsLocations =
      ProfilingUtils.timed(s"Generate ${households.size} locations", x => logger.info(x)) {
        blockGroupGeoIdToHouseholds.par.map { case (blockGroupGeoId, householdsWithPersonData) =>
          val blockGeomOfHousehold = geoSvc.blockGroupGeoIdToGeom(blockGroupGeoId)
          blockGroupGeoId -> pointsGenerator.generate(
            blockGeomOfHousehold,
            householdsWithPersonData.size
          )
        }.seq
      }

    val nextWorkLocation = mutable.HashMap[PowPumaGeoId, Int]()
    val finalResult = blockGroupGeoIdToHouseholds.map { case (blockGroupGeoId, householdsWithPersonData) =>
      logger.info(s"BlockGroupId $blockGroupGeoId contains ${householdsWithPersonData.size} households")
      val householdLocation = blockGroupGeoIdToHouseholdsLocations(blockGroupGeoId)
      if (householdLocation.size != householdsWithPersonData.size) {
        logger.warn(
          s"For BlockGroupId $blockGroupGeoId generated ${householdLocation.size} locations, but the number of households is ${householdsWithPersonData.size}"
        )
      }
      val res = householdsWithPersonData.zip(householdLocation).flatMap {
        case ((household: Models.Household, personsWithData), wgsHouseholdLocation) =>
          if (mapBoundingBox.contains(wgsHouseholdLocation.getX, wgsHouseholdLocation.getY)) {
            val utmHouseholdCoord = geoUtils.wgs2Utm(wgsHouseholdLocation)
            val createdHousehold = HouseholdInfo(
              HouseholdId(household.id),
              household.numOfVehicles,
              household.income,
              utmHouseholdCoord.getX,
              utmHouseholdCoord.getY
            )

            val (personsAndPlans, lastPersonId) =
              personsWithData.foldLeft((List.empty[PersonWithPlans], globalPersonId)) {
                case ((xs, nextPersonId), PersonWithExtraInfoPuma(person, workDestPumaGeoId, timeLeavingHomeRange)) =>
                  val workLocations = powPumaGeoIdToWorkingLocations(workDestPumaGeoId)
                  val offset = nextWorkLocation.getOrElse(workDestPumaGeoId, 0)
                  nextWorkLocation.update(workDestPumaGeoId, offset + 1)
                  workLocations.lift(offset) match {
                    case Some(wgsWorkingLocation) =>
                      if (mapBoundingBox.contains(wgsWorkingLocation.getX, wgsWorkingLocation.getY)) {
                        val valueOfTime =
                          PopulationAdjustment.incomeToValueOfTime(household.income).getOrElse(defaultValueOfTime)
                        val createdPerson = beam.utils.scenario.PersonInfo(
                          personId = PersonId(nextPersonId.toString),
                          householdId = createdHousehold.householdId,
                          rank = 0,
                          age = person.age,
                          excludedModes = Seq.empty,
                          isFemale = person.gender == Gender.Female,
                          valueOfTime = valueOfTime
                        )
                        val timeLeavingHomeSeconds = drawTimeLeavingHome(timeLeavingHomeRange)

                        // Create Home Activity: end time is when a person leaves a home
                        val leavingHomeActivity = planElementTemplate.copy(
                          personId = createdPerson.personId,
                          planElementType = "activity",
                          planElementIndex = 1,
                          activityType = Some("Home"),
                          activityLocationX = Some(utmHouseholdCoord.getX),
                          activityLocationY = Some(utmHouseholdCoord.getY),
                          activityEndTime = Some(timeLeavingHomeSeconds / 3600.0),
                          geoId = Some(household.geoId.asUniqueKey)
                        )
                        // Create Leg
                        val leavingHomeLeg = planElementTemplate
                          .copy(personId = createdPerson.personId, planElementType = "leg", planElementIndex = 2)

                        val utmWorkingLocation = geoUtils.wgs2Utm(wgsWorkingLocation)
                        val margin = 1.3
                        val travelTime =
                          estimateTravelTime(timeLeavingHomeSeconds, utmHouseholdCoord, utmWorkingLocation, margin)
                        val workStartTime = timeLeavingHomeSeconds + travelTime
                        val workingDuration = workedDurationGeneratorImpl.next(timeLeavingHomeRange)
                        val timeLeavingWorkSeconds = workStartTime + workingDuration

                        val leavingWorkActivity = planElementTemplate.copy(
                          personId = createdPerson.personId,
                          planElementType = "activity",
                          planElementIndex = 3,
                          activityType = Some("Work"),
                          activityLocationX = Some(utmWorkingLocation.getX),
                          activityLocationY = Some(utmWorkingLocation.getY),
                          activityEndTime = Some(timeLeavingWorkSeconds / 3600.0)
                        )
                        val leavingWorkLeg = planElementTemplate
                          .copy(personId = createdPerson.personId, planElementType = "leg", planElementIndex = 4)

                        // Create Home Activity: end time not defined
                        val homeActivity = planElementTemplate.copy(
                          personId = createdPerson.personId,
                          planElementType = "activity",
                          planElementIndex = 5,
                          activityType = Some("Home"),
                          activityLocationX = Some(utmWorkingLocation.getX),
                          activityLocationY = Some(utmWorkingLocation.getY)
                        )

                        val personWithPlans = PersonWithPlans(
                          createdPerson,
                          List(leavingHomeActivity, leavingHomeLeg, leavingWorkActivity, leavingWorkLeg, homeActivity)
                        )
                        (personWithPlans :: xs, nextPersonId + 1)
                      } else {
                        logger
                          .info(
                            s"Working location $wgsWorkingLocation does not belong to bounding box $mapBoundingBox"
                          )
                        (xs, nextPersonId + 1)
                      }
                    case None =>
                      (xs, nextPersonId + 1)
                  }
              }
            globalPersonId = lastPersonId
            if (personsAndPlans.size == personsWithData.size) {
              Some((createdHousehold, personsAndPlans))
            } else None
          } else {
            logger.info(s"Household location $wgsHouseholdLocation does not belong to bounding box $mapBoundingBox")
            None
          }
      }
      blockGroupGeoId -> res
    }

    ScenarioResult(finalResult.values.flatten, Map.empty)
  }

  private def getBlockGroupToPuma: Map[BlockGroupGeoId, PumaGeoId] = {
    // TODO: This can be easily parallelize (very dummy improvement, in case if there is nothing better)
    val blockGroupToPuma = geoSvc.blockGroupGeoIdToGeom
      .flatMap { case (blockGroupGeoId, blockGroupGeom) =>
        // Intersect with all and get the best by the covered area
        val allIntersections = pumaGeoIdToGeom.map { case (pumaGeoId, pumaGeom) =>
          val intersection = blockGroupGeom.intersection(pumaGeom)
          (intersection, blockGroupGeoId, pumaGeoId)
        }
        val best = if (allIntersections.nonEmpty) Some(allIntersections.maxBy(x => x._1.getArea)) else None
        best
      }
      .map { case (_, blockGroupGeoId, pumaGeoId) =>
        blockGroupGeoId -> pumaGeoId
      }
      .toMap
    blockGroupToPuma
  }

  private def getBlockGroupIdToHouseholdAndPeople(
    blockGroupToPumaMap: Map[BlockGroupGeoId, PumaGeoId],
    geoIdToHouseholds: Map[BlockGroupGeoId, Iterable[Models.Household]]
  ): Map[BlockGroupGeoId, Iterable[(Models.Household, Seq[PersonWithExtraInfoPuma])]] = {
    val blockGroupGeoIdToHouseholds: Map[BlockGroupGeoId, Iterable[(Models.Household, Seq[PersonWithExtraInfoPuma])]] =
      geoIdToHouseholds.map { case (blockGroupGeoId, households) =>
        // TODO We need to bring building density in the future
        val pumaGeoIdOfHousehold: PumaGeoId = blockGroupToPumaMap(blockGroupGeoId)

        val timeLeavingODPairs = sourceToTimeLeavingOD(pumaGeoIdOfHousehold.asUniqueKey)
        val peopleInHouseholds = households.flatMap(x => householdIdToPersons(x.id))
        logger.info(s"In ${households.size} there are ${peopleInHouseholds.size} people")

        val householdsWithPersonData = households.map { household =>
          val persons = householdWithPersons(household)
          val personWithWorkDestAndTimeLeaving = persons.flatMap { person =>
            rndWorkDestinationGenerator.next(pumaGeoIdOfHousehold.asUniqueKey, household.income, rndGen).map {
              powPumaWorkDestStr =>
                val powPumaWorkDest = PowPumaGeoId.fromString(powPumaWorkDestStr)
                val foundDests = timeLeavingODPairs.filter(x => x.destination == powPumaWorkDest.asUniqueKey)
                if (foundDests.isEmpty) {
                  logger
                    .info(
                      s"Could not find work destination '$powPumaWorkDest' in ${timeLeavingODPairs.mkString(" ")}"
                    )
                  PersonWithExtraInfoPuma(
                    person = person,
                    workDest = powPumaWorkDest,
                    timeLeavingHomeRange = defaultTimeLeavingHomeRange
                  )
                } else {
                  val timeLeavingHomeRange =
                    ODSampler.sample(foundDests, rndGen).map(_.attribute).getOrElse(defaultTimeLeavingHomeRange)
                  PersonWithExtraInfoPuma(
                    person = person,
                    workDest = powPumaWorkDest,
                    timeLeavingHomeRange = timeLeavingHomeRange
                  )
                }
            }
          }
          if (personWithWorkDestAndTimeLeaving.size != persons.size) {
            logger.warn(
              s"Seems like the data for the persons not fully created. Original number of persons: ${persons.size}, but personWithWorkDestAndTimeLeaving size is ${personWithWorkDestAndTimeLeaving.size}"
            )
          }
          (household, personWithWorkDestAndTimeLeaving)
        }
        blockGroupGeoId -> householdsWithPersonData
      }
    blockGroupGeoIdToHouseholds
  }

  private def drawTimeLeavingHome(timeLeavingHomeRange: Range): Int = {
    // Randomly pick a number between [start, end]
    val howMany = timeLeavingHomeRange.end - timeLeavingHomeRange.start + 1
    timeLeavingHomeRange.start + rndGen.nextInt(howMany)
  }

  private def estimateTravelTime(
    timeLeavingHomeSeconds: Int,
    utmHouseholdCoord: Coord,
    workingLocation: Coord,
    margin: Double
  ): Double = {
    val distance = geoUtils.distUTMInMeters(utmHouseholdCoord, workingLocation) * margin
    val congestionLevel = (100 - congestionLevelData.level(timeLeavingHomeSeconds)) / 100
    val averageSpeed = offPeakSpeedMetersPerSecond * congestionLevel
    distance / averageSpeed
  }

  private def getBoundingBoxOfOsmMap(path: String): Envelope = {
    val osm = new OSM(null)
    try {
      osm.readFromFile(path)

      var minX = Double.MaxValue
      var maxX = Double.MinValue
      var minY = Double.MaxValue
      var maxY = Double.MinValue

      osm.nodes.values().forEach { x =>
        val lon = x.fixedLon / 10000000.0
        val lat = x.fixedLat / 10000000.0

        if (lon < minX) minX = lon
        if (lon > maxX) maxX = lon
        if (lat < minY) minY = lat
        if (lat > maxY) maxY = lat
      }
      new Envelope(minX, maxX, minY, maxY)
    } finally {
      Try(osm.close())
    }
  }
}

object PumaLevelScenarioGenerator {

  def main(args: Array[String]): Unit = {
    require(args.length == 10, "Expecting 10 arguments")
    val pathToHouseholdFile = args(0)
    val pathToPopulationFile = args(1)
    val pathToCTPPFolder = args(2)
    val pathToPumaShapeFile = args(3)
    val pathToPowPumaShapeFile = args(4)
    val pathToBlockGroupShapeFile = args(5)
    val pathToCongestionLevelDataFile = args(6)
    val pathToWorkedHours = args(7)
    val pathToOsmMap = args(8)
    val pathToOutput = args(9)

    /*
    Args:
      "D:\Work\beam\Austin\input\household_TX_Travis County.csv"
      "D:\Work\beam\Austin\input\people_TX_Travis County.csv"
      "D:\Work\beam\Austin\input\CTPP\48"
      "D:\Work\beam\Austin\input\tl_2014_48_puma10\tl_2014_48_puma10.shp"
      "D:\Work\beam\Austin\input\ipums_migpuma_pwpuma_2010\ipums_migpuma_pwpuma_2010.shp"
      "D:\Work\beam\Austin\input\tl_2019_48_bg\tl_2019_48_bg.shp"
      "D:\Work\beam\Austin\input\CongestionLevel_Austin.csv"
      "D:\Work\beam\Austin\input\work_activities_all_us.csv"
      "D:\Work\beam\Austin\texas-latest-simplified-austin-light-v5-incomplete-ways.osm.pbf"
      "D:\Work\beam\Austin\results"
     * */

    val databaseInfo = CTPPDatabaseInfo(PathToData(pathToCTPPFolder), Set("48"))

    val gen =
      new PumaLevelScenarioGenerator(
        pathToHouseholdFile,
        pathToPopulationFile,
        databaseInfo,
        pathToPumaShapeFile,
        pathToPowPumaShapeFile,
        pathToBlockGroupShapeFile,
        pathToCongestionLevelDataFile,
        pathToWorkedHours,
        pathToOsmMap,
        42
      )

    val scenarioResult = gen.generate
    val generatedData = scenarioResult.householdWithTheirPeople
    println(s"Number of households: ${generatedData.size}")
    println(s"Number of of people: ${generatedData.flatMap(_._2).size}")

    val households = generatedData.map(_._1).toVector
    val householdFilePath = s"$pathToOutput/households.csv"
    CsvHouseholdInfoWriter.write(householdFilePath, households)
    println(s"Wrote households information to $householdFilePath")
    val readHouseholds = CsvHouseholdInfoReader.read(householdFilePath)
    val areHouseholdsEqual = readHouseholds.toVector == households
    println(s"areHouseholdsEqual: $areHouseholdsEqual")

    val persons = generatedData.flatMap(_._2.map(_.person)).toVector
    val personsFilePath = s"$pathToOutput/persons.csv"
    CsvPersonInfoWriter.write(personsFilePath, persons)
    println(s"Wrote persons information to $personsFilePath")
    val readPersons = CsvPersonInfoReader.read(personsFilePath)
    val arePersonsEqual = readPersons.toVector == persons
    println(s"arePersonsEqual: $arePersonsEqual")

    val planElements = generatedData.flatMap(_._2.flatMap(_.plans)).toVector
    val plansFilePath = s"$pathToOutput/plans.csv"
    CsvPlanElementWriter.write(plansFilePath, planElements)
    println(s"Wrote plans information to $plansFilePath")
    val readPlanElements = CsvPlanElementReader.read(plansFilePath)
    val arePlanElementsEqual = readPlanElements.toVector == planElements
    println(s"arePlanElementsEqual: $arePlanElementsEqual")
  }
}
