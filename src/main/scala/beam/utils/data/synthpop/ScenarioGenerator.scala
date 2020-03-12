package beam.utils.data.synthpop

import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.{PointGenerator, RandomPointsInGridGenerator}
import beam.utils.ProfilingUtils
import beam.utils.csv.CsvWriter
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.PathToData
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
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Envelope
import org.apache.commons.math3.random.MersenneTwister
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
  val pathToCTPPFolder: String,
  val pathToTazShapeFile: String,
  val pathToBlockGroupShapeFile: String,
  val pathToCongestionLevelDataFile: String,
  val pathToWorkedHours: String,
  val pathToOsmMap: String,
  val stateCode: String,
  val randomSeed: Int,
  val offPeakSpeed: Double = 20.5638, // https://inrix.com/scorecard-city/?city=Austin%2C%20TX&index=84
  val defaultValueOfTime: Double = 8.0
) extends ScenarioGenerator
    with StrictLogging {

  logger.info(s"Initializing...")

  private val mapBoundingBox: Envelope = GeoService.getBoundingBoxOfOsmMap(pathToOsmMap)

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

  private val pathToCTPPData = PathToData(pathToCTPPFolder)
  private val rndWorkDestinationGenerator: RandomWorkDestinationGenerator =
    new RandomWorkDestinationGenerator(pathToCTPPData, new MersenneTwister(randomSeed))
  private val workedDurationGeneratorImpl: WorkedDurationGeneratorImpl =
    new WorkedDurationGeneratorImpl(pathToWorkedHours, new MersenneTwister(randomSeed))
  private val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography =
    ResidenceToWorkplaceFlowGeography.`TAZ To TAZ`

  private val timeLeavingHomeGenerator: TimeLeavingHomeGenerator =
    new TimeLeavingHomeGeneratorImpl(pathToCTPPData, residenceToWorkplaceFlowGeography)

  private val workForceSampler = new WorkForceSampler(pathToCTPPData, stateCode, new MersenneTwister(randomSeed))

  private val pointsGenerator: PointGenerator = new RandomPointsInGridGenerator(1.1)

  private val householdWithPersons: Map[Models.Household, Seq[Models.Person]] = {
    // Read households and people
    val temp = SythpopReader.apply(pathToSythpopDataFolder).read().toSeq
    // Take only with age is >= 16
    val elderThan16Years = temp
      .map {
        case (hh, persons) =>
          hh -> persons.filter(p => p.age >= 16)
      }
      .filter { case (_, persons) => persons.nonEmpty }
      .toMap
    val removedHh = temp.size - elderThan16Years.size
    val removedPeopleYoungerThan16 = temp.map(x => x._2.size).sum - elderThan16Years.values.map(x => x.size).sum
    logger.info(s"Read ${temp.size} households with ${temp.map(x => x._2.size).sum} people")
    logger.info(s"""After filtering them got ${elderThan16Years.size} households with ${elderThan16Years.values
                     .map(x => x.size)
                     .sum} people.
         |Removed $removedHh households and $removedPeopleYoungerThan16 people who are younger than 16""".stripMargin)

//    showAgeCounts(elderThan16Years)

    val finalResult = elderThan16Years.foldLeft(Map[Models.Household, Seq[Models.Person]]()) {
      case (acc, (hh, people)) =>
        val workers = people.collect { case person if workForceSampler.isWorker(person.age) => person }
        if (workers.isEmpty) acc
        else {
          acc + (hh -> workers)
        }
    }
    val removedEmptyHh = elderThan16Years.size - finalResult.size
    val removedNonWorkers = elderThan16Years.map(x => x._2.size).sum - finalResult.values.map(x => x.size).sum
    logger.info(s"""After applying work force sampler them got ${finalResult.size} households with ${finalResult.values
                     .map(x => x.size)
                     .sum} people.
         |Removed $removedEmptyHh households and $removedNonWorkers people""".stripMargin)

    finalResult
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
    GeoServiceInputParam(pathToTazShapeFile, pathToBlockGroupShapeFile),
    uniqueStates,
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
    val blockGroupGeoIdToHouseholds = getBlockGroupIdToHouseholdAndPeople

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
      case (powPumaGeoId, cnt) =>
        logger.info(s"$powPumaGeoId => $cnt")
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
        logger.info(s"BlockGroupId $blockGroupGeoId contains ${householdsWithPersonData.size} households")
        val householdLocation = blockGroupGeoIdToHouseholdsLocations(blockGroupGeoId)
        if (householdLocation.size != householdsWithPersonData.size) {
          logger.warn(
            s"For BlockGroupId $blockGroupGeoId generated ${householdLocation.size} locations, but the number of households is ${householdsWithPersonData.size}"
          )
        }
        val res = householdsWithPersonData.zip(householdLocation).map {
          case ((household, personsWithData), wgsHouseholdLocation) =>
            if (mapBoundingBox.contains(wgsHouseholdLocation.getX, wgsHouseholdLocation.getY)) {
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
                        if (mapBoundingBox.contains(wgsWorkingLocation.getX, wgsWorkingLocation.getY)) {
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
              None
            }
        }
        blockGroupGeoId -> res
    }

    val outOfBoundingBoxCnt = finalResult.values.flatten.count(x => x.isEmpty)
    if (outOfBoundingBoxCnt != 0)
      logger.warn(
        s"There were ${outOfBoundingBoxCnt} households which locations do not belong to bounding box $mapBoundingBox"
      )
    finalResult.values.flatten.flatten
  }

  private def getBlockGroupToTazs: Map[BlockGroupGeoId, List[TazGeoId]] = {
    // TODO: This can be easily parallelize (very dummy improvement, in case if there is nothing better)
    val blockGroupToTazs = geoSvc.blockGroupGeoIdToGeom
      .map {
        case (blockGroupGeoId, blockGroupGeom) =>
          // Intersect with all and get the best by the covered area
          val allIntersections = geoSvc.tazGeoIdToGeom.map {
            case (tazGeoId, tazGeo) =>
              val intersection = blockGroupGeom.intersection(tazGeo)
              (intersection, blockGroupGeoId, tazGeoId)
          }
          blockGroupGeoId -> allIntersections.map(_._3).toList
      }
    blockGroupToTazs
  }

  def findWorkingLocation(
    tazGeoId: TazGeoId,
    households: Iterable[Models.Household]
  ): Iterable[Seq[Option[PersonWithExtraInfo]]] = {
    val personData: Iterable[Seq[Option[PersonWithExtraInfo]]] =
      households.map { household =>
        val persons = householdWithPersons(household)
        val personWithWorkDestAndTimeLeaving = persons.flatMap { person =>
          rndWorkDestinationGenerator.next(tazGeoId.asUniqueKey, household.income).map { tazWorkDestStr =>
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

  private def getBlockGroupIdToHouseholdAndPeople
    : Map[BlockGroupGeoId, Iterable[(Models.Household, Seq[PersonWithExtraInfo])]] = {
    val blockGroupGeoIdToHouseholds: Map[BlockGroupGeoId, Iterable[(Models.Household, Seq[PersonWithExtraInfo])]] =
      geoIdToHouseholds.toSeq.zipWithIndex.map {
        case ((blockGroupGeoId, households), index) =>
          val tazes = blockGroupToToTazs(blockGroupGeoId)
          // It is one to many relation
          // BlockGroupGeoId1 -> TAZ1
          // BlockGroupGeoId1 -> TAZ2
          // BlockGroupGeoId1 -> TAZ3
          // So let's generate all possible combinations and choose randomly from them
          val allPossibleLocations: List[PersonWithExtraInfo] = tazes.flatMap { tazGeoId =>
            findWorkingLocation(tazGeoId, households).flatten.flatten
          }
          val uniquePersons = randomlyChooseUniquePersons(allPossibleLocations)

          val householdWithPersons = uniquePersons
            .map(p => (personIdToHousehold(p.person), p))
            .groupBy { case (hh, xs) => hh }
            .map { case (hh, xs) => (hh, xs.map(_._2)) }
            .toSeq

          logger.info(
            s"$blockGroupGeoId associated ${householdWithPersons.size} households with ${uniquePersons.size} people, ${index + 1} out of ${geoIdToHouseholds.size}"
          )
          blockGroupGeoId -> householdWithPersons
      }.toMap
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
    val averageSpeed = offPeakSpeed * congestionLevel
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

  private def showAgeCounts(hhToPeople: Map[Models.Household, Seq[Models.Person]]): Unit = {
    val ages = hhToPeople.values.flatten
      .map { person =>
        person.age
      }
      .groupBy(x => x)
      .toSeq
      .map { case (age, xs) => (age, xs.size) }
      .sortBy { case (age, _) => age }
    ages.foreach {
      case (age, cnt) =>
        logger.info(s"Age: $age, count: $cnt")
    }
  }
}

object SimpleScenarioGenerator {

  def main(args: Array[String]): Unit = {
    require(args.length == 9, s"Expecting 8 arguments, but got ${args.length}")
    val pathToSythpopDataFolder = args(0)
    val pathToCTPPFolder = args(1)
    val pathToTazShapeFile = args(2)
    val pathToBlockGroupShapeFile = args(3)
    val pathToCongestionLevelDataFile = args(4)
    val pathToWorkedHours = args(5)
    val pathToOsmMap = args(6)
    val pathToOutput = args(7)
    val stateCode = args(8)

    /*
    Args:
      "D:\Work\beam\Austin\input\"
      "D:\Work\beam\Austin\input\CTPP\48"
      "D:\Work\beam\Austin\input\tl_2011_48_taz10\tl_2011_48_taz10.shp"
      "D:\Work\beam\Austin\input\tl_2019_48_bg\tl_2019_48_bg.shp"
      "D:\Work\beam\Austin\input\CongestionLevel_Austin.csv"
      "D:\Work\beam\Austin\input\work_activities_all_us.csv"
      "D:\Work\beam\Austin\input\texas-six-counties-simplified.osm.pbf"
      "D:\Work\beam\Austin\results"
      "48"
     * */

    val gen =
      new SimpleScenarioGenerator(
        pathToSythpopDataFolder = pathToSythpopDataFolder,
        pathToCTPPFolder = pathToCTPPFolder,
        pathToTazShapeFile = pathToTazShapeFile,
        pathToBlockGroupShapeFile = pathToBlockGroupShapeFile,
        pathToCongestionLevelDataFile = pathToCongestionLevelDataFile,
        pathToWorkedHours = pathToWorkedHours,
        pathToOsmMap = pathToOsmMap,
        stateCode = stateCode,
        randomSeed = 42,
      )

    gen.writeTazCenters(pathToOutput)

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
}
