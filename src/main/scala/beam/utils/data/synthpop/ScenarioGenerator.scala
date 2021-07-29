package beam.utils.data.synthpop

import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.AtomicInteger

import beam.agentsim.infrastructure.geozone._
import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.{PointGenerator, RandomPointsInGridGenerator}
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.synthpop.GeoService.CheckResult
import beam.utils.data.synthpop.generators.{
  RandomWorkDestinationGenerator,
  TimeLeavingHomeGenerator,
  TimeLeavingHomeGeneratorImpl,
  WorkedDurationGeneratorImpl
}
import beam.utils.data.synthpop.models.Models
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, County, Gender, GenericGeoId, State, TazGeoId}
import beam.utils.scenario._
import beam.utils.scenario.generic.writers.{
  CsvHouseholdInfoWriter,
  CsvParkingInfoWriter,
  CsvPersonInfoWriter,
  CsvPlanElementWriter
}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.math3.random.{MersenneTwister, RandomGenerator}
import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.util.{Random, Try}

trait ScenarioGenerator {
  def generate: ScenarioResult
}

case class PersonWithExtraInfo(
  person: Models.Person,
  homeLoc: TazGeoId,
  workDest: TazGeoId,
  timeLeavingHomeRange: Range
)
case class PersonWithPlans(person: PersonInfo, plans: List[PlanElement])

case class ScenarioResult(
  householdWithTheirPeople: Iterable[(HouseholdInfo, List[PersonWithPlans])],
  geoIdToAreaResidentsAndWorkers: Map[GenericGeoId, (Int, Int)]
)

class SimpleScenarioGenerator(
  val pathToSythpopDataFolder: String,
  val dbInfo: CTPPDatabaseInfo,
  val pathToTazShapeFile: String,
  val pathToBlockGroupShapeFile: String,
  val pathToCongestionLevelDataFile: String, // One can create it manually https://www.tomtom.com/en_gb/traffic-index/austin-traffic/
  val pathToWorkedHours: String, // Conditional work duration
  val pathToOsmMap: String,
  val randomSeed: Int,
  val offPeakSpeedMetersPerSecond: Double,
  val localCoordinateReferenceSystem: String,
  val defaultValueOfTime: Double = 8.0
) extends ScenarioGenerator
    with StrictLogging {

  logger.info(s"Initializing...")

  private val rndGen: MersenneTwister = new MersenneTwister(randomSeed) // Random.org

  private val geoUtils: GeoUtils = new GeoUtils {
    override def localCRS: String = localCoordinateReferenceSystem
  }

  private val defaultTimeLeavingHomeRange: Range = Range(6 * 3600, 7 * 3600)

  private val congestionLevelData: CsvCongestionLevelData = new CsvCongestionLevelData(pathToCongestionLevelDataFile)

  private val planElementTemplate: PlanElement = PlanElement(
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
    ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`

  private val timeLeavingHomeGenerator: TimeLeavingHomeGenerator =
    new TimeLeavingHomeGeneratorImpl(dbInfo, residenceToWorkplaceFlowGeography)

  private val stateCodeToWorkForceSampler: Map[String, WorkForceSampler] = dbInfo.states.map { stateCode =>
    stateCode -> new WorkForceSampler(dbInfo, stateCode, new MersenneTwister(randomSeed))
  }.toMap

  private val pointsGenerator: PointGenerator = new RandomPointsInGridGenerator(1.1)

  private val householdWithPersons: Map[Models.Household, Seq[Models.Person]] = {
    // Read households and people
    val temp: Seq[(Models.Household, Seq[Models.Person])] = SythpopReader.apply(pathToSythpopDataFolder).read().toSeq
    // Adjust population
    PopulationCorrection.adjust(temp, stateCodeToWorkForceSampler)
  }

  private val personIdToHousehold: Map[Models.Person, Models.Household] = householdWithPersons.flatMap {
    case (hhId, persons) =>
      persons.map { p =>
        p -> hhId
      }
  }

  private val households = householdWithPersons.keySet

  logger.info(s"Total number of households: ${householdWithPersons.size}")
  logger.info(s"Total number of people: ${householdWithPersons.map(_._2.size).sum}")

  private val geoIdToHouseholds = households.toSeq.groupBy(x => x.geoId)
  private val uniqueGeoIds = geoIdToHouseholds.keySet
  logger.info(s"uniqueGeoIds: ${uniqueGeoIds.size}")

  private val uniqueStates: Set[State] = households.map(_.geoId.state)
  logger.info(s"uniqueStates: ${uniqueStates.size}")

  private val geoSvc: GeoService = new GeoService(
    GeoServiceInputParam(pathToTazShapeFile, pathToBlockGroupShapeFile, pathToOsmMap),
    uniqueGeoIds,
    geoUtils
  )

  val blockGroupToToTazs: Map[BlockGroupGeoId, List[TazGeoId]] = ProfilingUtils.timed(
    s"getBlockGroupToTazs for blockGroupGeoIdToGeom ${geoSvc.blockGroupGeoIdToGeom.size} and tazGeoIdToGeom ${geoSvc.tazGeoIdToGeom.size}",
    x => logger.info(x)
  ) {
    getBlockGroupToTazs
  }
  logger.info(s"blockGroupToToTazs: ${blockGroupToToTazs.size}")

  logger.info(s"Initializing finished")

  override def generate: ScenarioResult = {
    logger.info(s"Generating BlockGroupId to Households and their people")
    val blockGroupGeoIdToHouseholds = ProfilingUtils.timed("assignWorkingLocations", x => logger.info(x)) {
      assignWorkingLocations
    }

    val geoIdToAreaResidentsAndWorkers: Map[GenericGeoId, (Int, Int)] = blockGroupGeoIdToHouseholds.values
      .flatMap { x: Iterable[(Models.Household, Seq[PersonWithExtraInfo])] =>
        x.flatMap { hh: (Models.Household, Seq[PersonWithExtraInfo]) =>
          hh._2.map { y: PersonWithExtraInfo =>
            (y.homeLoc, y.workDest)
          }
        }
      }
      .foldLeft(mutable.Map.empty[GenericGeoId, (Int, Int)].withDefaultValue((0, 0)))((acc, tazs) => {
        acc.put(tazs._1, (acc(tazs._1)._1 + 1, acc(tazs._1)._2))
        acc.put(tazs._2, (acc(tazs._2)._1, acc(tazs._2)._2 + 1))
        acc
      })
      .toMap
    logger.info(s"Finished taz mapping")

    // Build work destination to the number of occurrences
    // We need this to be able to generate random work destinations inside geometry.
    val allWorkingDestinations = blockGroupGeoIdToHouseholds.values.flatMap { x =>
      x.flatMap { case (_, xs) => xs.map(_.workDest) }
    }
    val tazGeoIdToOccurrences = allWorkingDestinations.foldLeft(Map[TazGeoId, Int]()) { case (acc, c) =>
      val occur = acc.getOrElse(c, 0) + 1
      acc.updated(c, occur)
    }
    logger.info(s"allWorkingDestinations: ${allWorkingDestinations.size}")
    logger.info(s"tazGeoIdToOccurrences: ${tazGeoIdToOccurrences.size}")
    tazGeoIdToOccurrences.foreach { case (tazGeoId, cnt) =>
      logger.info(s"$tazGeoId => $cnt")
    }
    logger.info(s"Generating TAZ geo id to working locations...")

    val nPointsMultiplier: Double = 3.0

    // Generate all work destinations which will be later assigned to people
    val tazGeoIdToWorkingLocations =
      ProfilingUtils.timed("Generated TAZ geo id to working locations", x => logger.info(x)) {
        tazGeoIdToOccurrences.par.map { case (tazGeoId: TazGeoId, nWorkingPlaces) =>
          val workingGeos = geoSvc.tazGeoIdToGeom.get(tazGeoId) match {
            case Some(geom) =>
              ProfilingUtils.timed(s"Generate $nWorkingPlaces geo points in $tazGeoId", x => logger.info(x)) {
                val initLocations = pointsGenerator.generate(geom, (nPointsMultiplier * nWorkingPlaces).toInt)
                val withinMapConstrains = initLocations.filter(c =>
                  geoSvc.coordinatesWithinBoundaries(c) == CheckResult.InsideBoundingBoxAndFeasbleForR5
                )
                val finalLocations = withinMapConstrains.take(nWorkingPlaces)
                logger.info(
                  s"$tazGeoId: Generated ${initLocations.length} initial locations and ${withinMapConstrains.length} are within map, finalLocations: ${finalLocations.length}"
                )
                finalLocations
              }
            case None =>
              logger.warn(s"Can't find $tazGeoId in `tazGeoIdToGeom`")
              Seq.empty
          }
          tazGeoId -> workingGeos
        }.seq
      }

    logger.info(s"Generating BlockGroup geo id to household locations...")
    val blockGroupGeoIdToHouseholdsLocations =
      ProfilingUtils.timed(s"Generate ${households.size} household locations", x => logger.info(x)) {
        blockGroupGeoIdToHouseholds.par.map { case (blockGroupGeoId, householdsWithPersonData) =>
          val blockGeomOfHousehold = geoSvc.blockGroupGeoIdToGeom(blockGroupGeoId)
          val initLocations =
            pointsGenerator.generate(blockGeomOfHousehold, (nPointsMultiplier * householdsWithPersonData.size).toInt)
          val withinMapConstrains = initLocations.filter(c =>
            geoSvc.coordinatesWithinBoundaries(c) == CheckResult.InsideBoundingBoxAndFeasbleForR5
          )
          val finalLocations = withinMapConstrains.take(householdsWithPersonData.size)
          logger.info(
            s"$blockGroupGeoId: Generated ${initLocations.length} initial locations and ${withinMapConstrains.length} are within map, finalLocations: ${finalLocations.length}"
          )
          blockGroupGeoId -> finalLocations
        }.seq
      }

    var globalPersonId: Int = 0
    val nextWorkLocation = mutable.HashMap[TazGeoId, Int]()
    var cnt: Int = 0
    val finalResult = blockGroupGeoIdToHouseholds.map {
      case (blockGroupGeoId, householdsWithPersonData: Iterable[(Models.Household, Seq[PersonWithExtraInfo])]) =>
        val pct = "%.3f".format(100 * cnt.toDouble / blockGroupGeoIdToHouseholds.size)
        logger.info(
          s"$blockGroupGeoId contains ${householdsWithPersonData.size} households. $cnt out of ${blockGroupGeoIdToHouseholds.size}, pct: $pct%"
        )
        val householdLocation = blockGroupGeoIdToHouseholdsLocations(blockGroupGeoId)
        if (householdLocation.size != householdsWithPersonData.size) {
          logger.warn(
            s"For BlockGroupId $blockGroupGeoId generated ${householdLocation.size} locations, but the number of households is ${householdsWithPersonData.size}"
          )
        }
        val res = householdsWithPersonData.zip(householdLocation).map {
          case ((household: Models.Household, personsWithData: Seq[PersonWithExtraInfo]), wgsHouseholdLocation) =>
            val createdHousehold = HouseholdInfo(
              HouseholdId(household.fullId),
              household.numOfVehicles,
              household.income,
              wgsHouseholdLocation.getX,
              wgsHouseholdLocation.getY
            )

            val (personsAndPlans, lastPersonId) =
              personsWithData.foldLeft((List.empty[PersonWithPlans], globalPersonId)) {
                case (
                      (xs, nextPersonId: Int),
                      PersonWithExtraInfo(person, homeLocGeoId, workTazGeoId, timeLeavingHomeRange)
                    ) =>
                  val workLocations = tazGeoIdToWorkingLocations(workTazGeoId)
                  val offset = nextWorkLocation.getOrElse(workTazGeoId, 0)
                  nextWorkLocation.update(workTazGeoId, offset + 1)
                  workLocations.lift(offset) match {
                    case Some(wgsWorkingLocation) =>
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
                        activityLocationX = Some(wgsHouseholdLocation.getX),
                        activityLocationY = Some(wgsHouseholdLocation.getY),
                        activityEndTime = Some(timeLeavingHomeSeconds / 3600.0),
                        geoId = Some(toTazGeoId(homeLocGeoId.state, homeLocGeoId.county, homeLocGeoId.taz))
                      )
                      // Create Leg
                      val leavingHomeLeg = planElementTemplate
                        .copy(personId = createdPerson.personId, planElementType = "leg", planElementIndex = 2)

                      val timeLeavingWorkSeconds = {
                        val utmHouseholdCoord = geoUtils.wgs2Utm(wgsHouseholdLocation)
                        val utmWorkingLocation = geoUtils.wgs2Utm(wgsWorkingLocation)
                        val margin = 1.3
                        val travelTime =
                          estimateTravelTime(timeLeavingHomeSeconds, utmHouseholdCoord, utmWorkingLocation, margin)
                        val workStartTime = timeLeavingHomeSeconds + travelTime
                        val workingDuration = workedDurationGeneratorImpl.next(timeLeavingHomeRange)
                        workStartTime + workingDuration
                      }

                      val leavingWorkActivity = planElementTemplate.copy(
                        personId = createdPerson.personId,
                        planElementType = "activity",
                        planElementIndex = 3,
                        activityType = Some("Work"),
                        activityLocationX = Some(wgsWorkingLocation.getX),
                        activityLocationY = Some(wgsWorkingLocation.getY),
                        activityEndTime = Some(timeLeavingWorkSeconds / 3600.0),
                        geoId = Some(toTazGeoId(workTazGeoId.state, workTazGeoId.county, workTazGeoId.taz))
                      )
                      val leavingWorkLeg = planElementTemplate
                        .copy(personId = createdPerson.personId, planElementType = "leg", planElementIndex = 4)

                      // Create Home Activity: end time not defined
                      val homeActivity = planElementTemplate.copy(
                        personId = createdPerson.personId,
                        planElementType = "activity",
                        planElementIndex = 5,
                        activityType = Some("Home"),
                        activityLocationX = Some(wgsHouseholdLocation.getX),
                        activityLocationY = Some(wgsHouseholdLocation.getY),
                        geoId = Some(toTazGeoId(homeLocGeoId.state, homeLocGeoId.county, homeLocGeoId.taz))
                      )

                      val personWithPlans = PersonWithPlans(
                        createdPerson,
                        List(leavingHomeActivity, leavingHomeLeg, leavingWorkActivity, leavingWorkLeg, homeActivity)
                      )
                      (personWithPlans :: xs, nextPersonId + 1)
                    case None =>
                      (xs, nextPersonId + 1)
                  }
              }
            globalPersonId = lastPersonId
            if (personsAndPlans.size == personsWithData.size) {
              Some((createdHousehold, personsAndPlans))
            } else None
        }
        cnt += 1
        blockGroupGeoId -> res
    }
    ScenarioResult(
      householdWithTheirPeople = finalResult.values.flatten.flatten,
      geoIdToAreaResidentsAndWorkers = geoIdToAreaResidentsAndWorkers
    )
  }

  private def getBlockGroupToTazs: Map[BlockGroupGeoId, List[TazGeoId]] = {
    // TODO: This can be easily parallelize (very dummy improvement, in case if there is nothing better)
    val blockGroupToTazs = geoSvc.blockGroupGeoIdToGeom
      .map { case (blockGroupGeoId, blockGroupGeom) =>
        // Intersect with all TAZ
        val allIntersections = geoSvc.tazGeoIdToGeom.flatMap { case (tazGeoId, tazGeo) =>
          val intersection = blockGroupGeom.intersection(tazGeo)
          if (intersection.isEmpty)
            None
          else Some((intersection, blockGroupGeoId, tazGeoId))
        }
        blockGroupGeoId -> allIntersections.map(_._3).toList
      }
    blockGroupToTazs
  }

  def findWorkingLocation(
    tazGeoId: TazGeoId,
    households: Iterable[Models.Household],
    rndGen: RandomGenerator
  ): Iterable[Seq[Option[PersonWithExtraInfo]]] = {
    val personData: Iterable[Seq[Option[PersonWithExtraInfo]]] =
      households.map { household =>
        val persons = householdWithPersons(household)
        val personWithWorkDestAndTimeLeaving = persons.flatMap { person =>
          rndWorkDestinationGenerator.next(tazGeoId.asUniqueKey, household.income, rndGen).map { tazWorkDestStr =>
            val tazWorkDest = TazGeoId.fromString(tazWorkDestStr)
            val foundDests = timeLeavingHomeGenerator.find(tazGeoId.asUniqueKey, tazWorkDest.asUniqueKey)
            if (foundDests.isEmpty) {
//                logger
//                  .info(
//                    s"Could not find work destination '${tazWorkDest}' in ${timeLeavingODPairs.mkString(" ")}"
//                  )
              None
            } else {
              val timeLeavingHomeRange =
                ODSampler.sample(foundDests, rndGen).map(_.attribute).getOrElse(defaultTimeLeavingHomeRange)
              Some(
                PersonWithExtraInfo(
                  person = person,
                  homeLoc = tazGeoId,
                  workDest = tazWorkDest,
                  timeLeavingHomeRange = timeLeavingHomeRange
                )
              )
            }
          }
        }
//          if (personWithWorkDestAndTimeLeaving.size != persons.size) {
//            logger.warn(
//              s"Seems like the data for the persons not fully created. Original number of persons: ${persons.size}, but personWithWorkDestAndTimeLeaving size is ${personWithWorkDestAndTimeLeaving.size}"
//            )
//          }
        personWithWorkDestAndTimeLeaving
      }
    personData
  }

  private def assignWorkingLocations: Map[BlockGroupGeoId, Iterable[(Models.Household, Seq[PersonWithExtraInfo])]] = {
    val numberOfProcessed = new AtomicInteger(0)
    val blockGroupGeoIdToHouseholds: Map[BlockGroupGeoId, Iterable[(Models.Household, Seq[PersonWithExtraInfo])]] =
      geoIdToHouseholds.toSeq.par // Process in parallel!
        .map { // We process it in parallel, but there is no shared state
          case (blockGroupGeoId, households) =>
            val rndGen =
              new MersenneTwister(
                randomSeed
              ) // It is important to create random generator here because `MersenneTwister` is not thread-safe
            val tazes = blockGroupToToTazs(blockGroupGeoId)
            // It is one to many relation
            // BlockGroupGeoId1 -> TAZ1
            // BlockGroupGeoId1 -> TAZ2
            // BlockGroupGeoId1 -> TAZ3
            // So let's generate all possible combinations and choose randomly from them
            val allPossibleLocations: List[PersonWithExtraInfo] = tazes.flatMap { tazGeoId =>
              findWorkingLocation(tazGeoId, households, rndGen).flatten.flatten
            }
            val uniquePersons = randomlyChooseUniquePersons(allPossibleLocations)

            val householdWithPersons = uniquePersons
              .map(p => (personIdToHousehold(p.person), p))
              .groupBy { case (hh, _) => hh }
              .map { case (hh, xs) => (hh, xs.map(_._2)) }
              .toSeq
            val processed = numberOfProcessed.incrementAndGet()
            logger.info(
              s"$blockGroupGeoId associated ${householdWithPersons.size} households with ${uniquePersons.size} people, $processed out of ${geoIdToHouseholds.size}"
            )
            blockGroupGeoId -> householdWithPersons
        }
        .seq
        .toMap
    blockGroupGeoIdToHouseholds
  }

  private def randomlyChooseUniquePersons(
    allPossibleLocations: List[PersonWithExtraInfo]
  ): List[PersonWithExtraInfo] = {
    allPossibleLocations
      .groupBy { p =>
        p.person
      }
      .values
      .map(xs => new Random(randomSeed).shuffle(xs).head)
      .toList
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

  def writeTazCenters(pathToFolder: String): Unit = {
    val pathToFile = pathToFolder + "/taz-centers.csv.gz"
    val csvWriter = new CsvWriter(pathToFile, Array("taz", "coord-x", "coord-y", "area"))
    try {
      geoSvc.tazGeoIdToGeom.foreach { case (taz, geo) =>
        val utmCoord = geoUtils.wgs2Utm(new Coord(geo.getCentroid.getX, geo.getCentroid.getY))
        csvWriter.write(taz.asUniqueKey, utmCoord.getX, utmCoord.getY, geo.getArea)
      }
    } finally {
      Try(csvWriter.close())
    }
  }

  def writeH3(pathToFolder: String, wgsCoords: Seq[Coord], expectedNumberOfBuckets: Int): Unit = {
    val wgsCoordinates = wgsCoords.map(WgsCoordinate.apply).toSet
    val summary: GeoZoneSummary = TopDownEqualDemandH3IndexMapper
      .from(new GeoZone(wgsCoordinates).includeBoundBoxPoints, expectedNumberOfBuckets)
      .generateSummary()

    logger.info(s"Created ${summary.items.length} H3 indexes from ${wgsCoordinates.size} unique coordinates")

    val resolutionToPoints = summary.items
      .map(x => x.index.resolution -> x.size)
      .groupBy { case (res, _) => res }
      .toSeq
      .map { case (res, xs) => res -> xs.map(_._2).sum }
      .sortBy { case (_, size) => -size }
    resolutionToPoints.foreach { case (res, size) =>
      logger.info(s"Resolution: $res, number of points: $size")
    }
    val h3Indexes = summary.items.sortBy(x => -x.size)

    val pathToFile = pathToFolder + "/h3-centers.csv.gz"
    val csvWriter = new CsvWriter(pathToFile, Array("taz", "coord-x", "coord-y", "area"))
    try {
      h3Indexes.foreach { case GeoZoneSummaryItem(h3Index: H3Index, _) =>
        val utmCoord = geoUtils.wgs2Utm(H3Wrapper.wgsCoordinate(h3Index).coord)
        val area = H3Wrapper.areaInM2(h3Index)
        csvWriter.write(h3Index.value, utmCoord.getX, utmCoord.getY, area)
      }
    } finally {
      Try(csvWriter.close())
    }
  }

  def toTazGeoId(state: State, county: County, taz: String): String = {
    s"${state.value}-${county.value}-$taz"
  }

  def toStateGeoId(state: State, county: County): String = {
    s"${state.value}-${county.value}"
  }

}

object SimpleScenarioGenerator extends StrictLogging {

  case class Arguments(
    sythpopDataFolder: String,
    ctppFolder: String,
    stateCodes: Set[String],
    tazShapeFolder: String,
    blockGroupShapeFolder: String,
    congestionLevelDataFile: String,
    workDurationCsv: String,
    osmMap: String,
    randomSeed: Int,
    offPeakSpeedMetersPerSecond: Double,
    defaultValueOfTime: Double,
    localCRS: String,
    outputFolder: String
  )

  def getCurrentDateTime: String = {
    DateTimeFormatter.ofPattern("MM-dd-yyyy_HH-mm-ss").format(LocalDateTime.now)
  }

  def run(parsedArgs: Arguments): Unit = {
    val pathToOutput = parsedArgs.outputFolder + "_" + getCurrentDateTime
    val databaseInfo = CTPPDatabaseInfo(PathToData(parsedArgs.ctppFolder), parsedArgs.stateCodes)
    require(new File(pathToOutput).mkdirs(), s"$pathToOutput exists, stopping...")

    val gen =
      new SimpleScenarioGenerator(
        pathToSythpopDataFolder = parsedArgs.sythpopDataFolder,
        dbInfo = databaseInfo,
        pathToTazShapeFile = parsedArgs.tazShapeFolder,
        pathToBlockGroupShapeFile = parsedArgs.blockGroupShapeFolder,
        pathToCongestionLevelDataFile = parsedArgs.congestionLevelDataFile,
        pathToWorkedHours = parsedArgs.workDurationCsv,
        pathToOsmMap = parsedArgs.osmMap,
        randomSeed = parsedArgs.randomSeed,
        offPeakSpeedMetersPerSecond = parsedArgs.offPeakSpeedMetersPerSecond,
        localCoordinateReferenceSystem = parsedArgs.localCRS,
        defaultValueOfTime = parsedArgs.defaultValueOfTime
      )

    gen.writeTazCenters(pathToOutput)

    val scenarioResult = gen.generate
    val generatedData = scenarioResult.householdWithTheirPeople
    logger.info(s"Number of households: ${generatedData.size}")
    logger.info(s"Number of of people: ${generatedData.flatMap(_._2).size}")

    val parkingFilePath = s"$pathToOutput/taz-parking.csv"
    CsvParkingInfoWriter.write(parkingFilePath, gen.geoSvc, scenarioResult.geoIdToAreaResidentsAndWorkers)
    println(s"Wrote parking information to $parkingFilePath")

    val households = generatedData.map(_._1).toVector
    val householdFilePath = s"$pathToOutput/households.csv"
    CsvHouseholdInfoWriter.write(householdFilePath, households)
    logger.info(s"Wrote households information to $householdFilePath")

    val persons = generatedData.flatMap(_._2.map(_.person)).toVector
    val personsFilePath = s"$pathToOutput/persons.csv"
    CsvPersonInfoWriter.write(personsFilePath, persons)
    logger.info(s"Wrote persons information to $personsFilePath")

    val planElements = generatedData.flatMap(_._2.flatMap(_.plans)).toVector
    val plansFilePath = s"$pathToOutput/plans.csv"
    CsvPlanElementWriter.write(plansFilePath, planElements)
    logger.info(s"Wrote plans information to $plansFilePath")

    val geoUtils: GeoUtils = new GeoUtils {
      override def localCRS: String = parsedArgs.localCRS
    }
    val allActivities = planElements.filter(_.planElementType == "activity").map { plan =>
      geoUtils.utm2Wgs(new Coord(plan.activityLocationX.get, plan.activityLocationY.get))
    }
    gen.writeH3(pathToOutput, allActivities, 1000)
  }

  def main(args: Array[String]): Unit = {
    /*

    How to run it through gradle:
    ./gradlew :execute -PmaxRAM=20 -PmainClass=beam.utils.data.synthpop.SimpleScenarioGenerator -PappArgs=["
    '--sythpopDataFolder', 'D:/Work/beam/NewYork/input/syntpop',
    '--ctppFolder', 'D:/Work/beam/CTPP/',
    '--stateCodes', '34,36',
    '--tazShapeFolder', 'D:/Work/beam/NewYork/input/Shape/TAZ/',
    '--blockGroupShapeFolder', 'D:/Work/beam/NewYork/input/Shape/BlockGroup/',
    '--congestionLevelDataFile', 'D:/Work/beam/NewYork/input/CongestionLevel_NewYork.csv',
    '--workDurationCsv', 'D:/Work/beam/Austin/input/work_activities_all_us.csv',
    '--osmMap', 'D:/Work/beam/NewYork/input/OSM/newyork-simplified.osm.pbf',
    '--randomSeed', '42',
    '--offPeakSpeedMetersPerSecond', '12.5171',
    '--defaultValueOfTime', '8.0',
    '--localCRS', 'EPSG:3084',
    '--outputFolder', 'D:/Work/beam/NewYork/results'
    "] -PlogbackCfg=logback.xml
     */
    ProfilingUtils.timed("Scenario generation", x => logger.info(x)) {
      SimpleScenarioGeneratorArgParser.parseArguments(args) match {
        case None =>
          throw new IllegalStateException("Unable to parse arguments. Check the logs")
        case Some(parsedArgs: Arguments) =>
          run(parsedArgs)

      }
    }
  }
}
