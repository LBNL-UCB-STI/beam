package beam.utils.data.synthpop

import java.io.File
import java.util.concurrent.atomic.AtomicInteger

import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.{PointGenerator, RandomPointsInGridGenerator}
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.{CTPPDatabaseInfo, PathToData}
import beam.utils.data.synthpop.generators.{
  RandomWorkDestinationGenerator,
  TimeLeavingHomeGenerator,
  TimeLeavingHomeGeneratorImpl,
  WorkedDurationGeneratorImpl
}
import beam.utils.data.synthpop.models.Models
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, Gender, TazGeoId}
import beam.utils.scenario._
import beam.utils.scenario.generic.readers.{CsvHouseholdInfoReader, CsvPersonInfoReader, CsvPlanElementReader}
import beam.utils.scenario.generic.writers.{CsvHouseholdInfoWriter, CsvPersonInfoWriter, CsvPlanElementWriter}
import com.typesafe.scalalogging.StrictLogging
import org.apache.commons.math3.random.{MersenneTwister, RandomGenerator}
import org.matsim.api.core.v01.Coord

import scala.collection.mutable
import scala.util.{Random, Try}

trait ScenarioGenerator {
  def generate: Iterable[(HouseholdInfo, List[PersonWithPlans])]
}

case class PersonWithExtraInfo(person: Models.Person, workDest: TazGeoId, timeLeavingHomeRange: Range)
case class PersonWithPlans(person: PersonInfo, plans: List[PlanElement])

class SimpleScenarioGenerator(
  val pathToSythpopDataFolder: String,
  val dbInfo: CTPPDatabaseInfo,
  val pathToTazShapeFile: String,
  val pathToBlockGroupShapeFile: String,
  val pathToCongestionLevelDataFile: String, // One can create it manually https://www.tomtom.com/en_gb/traffic-index/austin-traffic/
  val pathToWorkedHours: String,
  val pathToOsmMap: String, // Conditional work duration
  val randomSeed: Int,
  val offPeakSpeedMetersPerSecond: Double,
  val defaultValueOfTime: Double = 8.0
) extends ScenarioGenerator
    with StrictLogging {

  logger.info(s"Initializing...")

  private val rndGen: MersenneTwister = new MersenneTwister(randomSeed) // Random.org

  private val geoUtils: GeoUtils = new GeoUtils {
    // TODO: Is it truth for all cases? Check the coverage https://epsg.io/26910
    // WGS84 bounds:
    //-172.54 23.81
    //-47.74 86.46
    override def localCRS: String = "epsg:26910"
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
    legRouteLinks = Seq.empty
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

  private val uniqueStates = households.map(_.geoId.state).toSet
  logger.info(s"uniqueStates: ${uniqueStates.size}")

  private val geoSvc: GeoService = new GeoService(
    GeoServiceInputParam(pathToTazShapeFile, pathToBlockGroupShapeFile, pathToOsmMap),
    uniqueGeoIds
  )

  val blockGroupToToTazs: Map[BlockGroupGeoId, List[TazGeoId]] = ProfilingUtils.timed(
    s"getBlockGroupToTazs for blockGroupGeoIdToGeom ${geoSvc.blockGroupGeoIdToGeom.size} and tazGeoIdToGeom ${geoSvc.tazGeoIdToGeom.size}",
    x => logger.info(x)
  ) {
    getBlockGroupToTazs
  }
  logger.info(s"blockGroupToPumaMap: ${blockGroupToToTazs.size}")

  logger.info(s"Initializing finished")

  override def generate: Iterable[(HouseholdInfo, List[PersonWithPlans])] = {
    var globalPersonId: Int = 0

    logger.info(s"Generating BlockGroupId to Households and their people")
    val blockGroupGeoIdToHouseholds = ProfilingUtils.timed("assignWorkingLocations", x => logger.info(x)) {
      assignWorkingLocations
    }

    // Build work destination to the number of occurrences
    // We need this to be able to generate random work destinations inside geometry.
    val allWorkingDestinations = blockGroupGeoIdToHouseholds.values.flatMap { x =>
      x.flatMap { case (_, xs) => xs.map(_.workDest) }
    }
    val tazGeoIdToOccurrences = allWorkingDestinations.foldLeft(Map[TazGeoId, Int]()) {
      case (acc, c) =>
        val occur = acc.getOrElse(c, 0) + 1
        acc.updated(c, occur)
    }
    logger.info(s"allWorkingDestinations: ${allWorkingDestinations.size}")
    logger.info(s"tazGeoIdToOccurrences: ${tazGeoIdToOccurrences.size}")
    tazGeoIdToOccurrences.foreach {
      case (tazGeoId, cnt) =>
        logger.info(s"$tazGeoId => $cnt")
    }
    logger.info(s"Generating TAZ geo id to working locations...")
    // Generate all work destinations which will be later assigned to people
    val tazGeoIdToWorkingLocations =
      ProfilingUtils.timed("Generated TAZ geo id to working locations", x => logger.info(x)) {
        tazGeoIdToOccurrences.par.map {
          case (tazGeoId: TazGeoId, nWorkingPlaces) =>
            val workingGeos = geoSvc.tazGeoIdToGeom.get(tazGeoId) match {
              case Some(geom) =>
                ProfilingUtils.timed(s"Generate ${nWorkingPlaces} geo points in ${tazGeoId}", x => logger.info(x)) {
                  pointsGenerator.generate(geom, nWorkingPlaces)
                }
              case None =>
                logger.warn(s"Can't find ${tazGeoId} in `tazGeoIdToGeom`")
                Seq.empty
            }
            tazGeoId -> workingGeos
        }.seq
      }

    logger.info(s"Generating BlockGroup geo id to household locations...")
    val blockGroupGeoIdToHouseholdsLocations =
      ProfilingUtils.timed(s"Generate ${households.size} household locations", x => logger.info(x)) {
        blockGroupGeoIdToHouseholds.par.map {
          case (blockGroupGeoId, householdsWithPersonData) =>
            val blockGeomOfHousehold = geoSvc.blockGroupGeoIdToGeom(blockGroupGeoId)
            blockGroupGeoId -> pointsGenerator.generate(
              blockGeomOfHousehold,
              householdsWithPersonData.size
            )
        }.seq
      }

    val nextWorkLocation = mutable.HashMap[TazGeoId, Int]()
    val finalResult = blockGroupGeoIdToHouseholds.map {
      case (blockGroupGeoId, householdsWithPersonData) =>
        logger.info(s"$blockGroupGeoId contains ${householdsWithPersonData.size} households")
        val householdLocation = blockGroupGeoIdToHouseholdsLocations(blockGroupGeoId)
        if (householdLocation.size != householdsWithPersonData.size) {
          logger.warn(
            s"For BlockGroupId $blockGroupGeoId generated ${householdLocation.size} locations, but the number of households is ${householdsWithPersonData.size}"
          )
        }
        val res = householdsWithPersonData.zip(householdLocation).map {
          case ((household, personsWithData), wgsHouseholdLocation) =>
            if (geoSvc.mapBoundingBox.contains(wgsHouseholdLocation.getX, wgsHouseholdLocation.getY)) {
              val utmHouseholdCoord = geoUtils.wgs2Utm(wgsHouseholdLocation)
              val createdHousehold = HouseholdInfo(
                HouseholdId(household.fullId),
                household.numOfVehicles,
                household.income,
                utmHouseholdCoord.getX,
                utmHouseholdCoord.getY
              )

              val (personsAndPlans, lastPersonId) =
                personsWithData.foldLeft((List.empty[PersonWithPlans], globalPersonId)) {
                  case ((xs, nextPersonId), PersonWithExtraInfo(person, workDestPumaGeoId, timeLeavingHomeRange)) =>
                    val workLocations = tazGeoIdToWorkingLocations(workDestPumaGeoId)
                    val offset = nextWorkLocation.getOrElse(workDestPumaGeoId, 0)
                    nextWorkLocation.update(workDestPumaGeoId, offset + 1)
                    workLocations.lift(offset) match {
                      case Some(wgsWorkingLocation) =>
                        if (geoSvc.mapBoundingBox.contains(wgsWorkingLocation.getX, wgsWorkingLocation.getY)) {
                          val valueOfTime =
                            PopulationAdjustment.incomeToValueOfTime(household.income).getOrElse(defaultValueOfTime)
                          val createdPerson = beam.utils.scenario.PersonInfo(
                            personId = PersonId(nextPersonId.toString),
                            householdId = createdHousehold.householdId,
                            rank = 0,
                            age = person.age,
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
                            activityEndTime = Some(timeLeavingHomeSeconds / 3600.0)
                          )
                          // Create Leg
                          val leavingHomeLeg = planElementTemplate
                            .copy(personId = createdPerson.personId, planElementType = "leg", planElementIndex = 2)

                          val utmWorkingLocation = geoUtils.wgs2Utm(wgsWorkingLocation)
                          val margin = 1.3
                          val distance = geoUtils.distUTMInMeters(utmHouseholdCoord, utmWorkingLocation) * margin
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
                            activityLocationX = Some(utmHouseholdCoord.getX),
                            activityLocationY = Some(utmHouseholdCoord.getY)
                          )

                          val personWithPlans = PersonWithPlans(
                            createdPerson,
                            List(leavingHomeActivity, leavingHomeLeg, leavingWorkActivity, leavingWorkLeg, homeActivity)
                          )
                          (personWithPlans :: xs, nextPersonId + 1)
                        } else {
                          logger
                            .info(
                              s"Working location $wgsWorkingLocation does not belong to bounding box ${geoSvc.mapBoundingBox}"
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
              None
            }
        }
        blockGroupGeoId -> res
    }

    val outOfBoundingBoxCnt = finalResult.values.flatten.count(x => x.isEmpty)
    if (outOfBoundingBoxCnt != 0)
      logger.warn(
        s"There were ${outOfBoundingBoxCnt} households which locations do not belong to bounding box ${geoSvc.mapBoundingBox}"
      )
    finalResult.values.flatten.flatten
  }

  private def getBlockGroupToTazs: Map[BlockGroupGeoId, List[TazGeoId]] = {
    // TODO: This can be easily parallelize (very dummy improvement, in case if there is nothing better)
    val blockGroupToTazs = geoSvc.blockGroupGeoIdToGeom
      .map {
        case (blockGroupGeoId, blockGroupGeom) =>
          // Intersect with all TAZ
          val allIntersections = geoSvc.tazGeoIdToGeom.flatMap {
            case (tazGeoId, tazGeo) =>
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
            val rndGen = new MersenneTwister(randomSeed) // It is important to create random generator here because `MersenneTwister` is not thread-safe
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
              .groupBy { case (hh, xs) => hh }
              .map { case (hh, xs) => (hh, xs.map(_._2)) }
              .toSeq
            val processed = numberOfProcessed.incrementAndGet()
            logger.info(
              s"$blockGroupGeoId associated ${householdWithPersons.size} households with ${uniquePersons.size} people, ${processed} out of ${geoIdToHouseholds.size}"
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
      .map {
        case (p, xs) =>
          new Random(randomSeed).shuffle(xs).head
      }
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
      geoSvc.tazGeoIdToGeom.foreach {
        case (taz, geo) =>
          val utmCoord = geoUtils.wgs2Utm(new Coord(geo.getCentroid.getX, geo.getCentroid.getY))
          csvWriter.write(taz.asUniqueKey, utmCoord.getX, utmCoord.getY, geo.getArea)
      }
    } finally {
      Try(csvWriter.close())
    }
  }

}

object SimpleScenarioGenerator {
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
    outputFolder: String
  )

  def run(parsedArgs: Arguments): Unit = {
    val pathToOutput = parsedArgs.outputFolder
    val databaseInfo = CTPPDatabaseInfo(PathToData(parsedArgs.ctppFolder), parsedArgs.stateCodes)
    require(new File(parsedArgs.outputFolder).mkdirs(), s"${pathToOutput} exists, stopping...")

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
        defaultValueOfTime = parsedArgs.defaultValueOfTime
      )

    gen.writeTazCenters(parsedArgs.outputFolder)

    val generatedData = gen.generate
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
    '--outputFolder', 'D:/Work/beam/NewYork/results'
    "] -PlogbackCfg=logback.xml
     */
    SimpleScenarioGeneratorArgParser.parseArguments(args) match {
      case None =>
        throw new IllegalStateException("Unable to parse arguments. Check the logs")
      case Some(parsedArgs: Arguments) =>
        run(parsedArgs)

    }
  }
}
