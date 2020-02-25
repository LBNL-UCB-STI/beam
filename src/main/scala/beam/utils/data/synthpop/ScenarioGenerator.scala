package beam.utils.data.synthpop

import java.io.File

import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.RandomPointsInGridGenerator
import beam.utils.csv.writers.{HouseholdsCsvWriter, PlansCsvWriter, PopulationCsvWriter}
import beam.utils.{ProfilingUtils, Statistics}
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.PathToData
import beam.utils.data.ctpp.readers.flow.TimeLeavingHomeTableReader
import beam.utils.data.synthpop.models.Models
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, Gender, PowPumaGeoId, PumaGeoId}
import com.conveyal.osmlib.OSM
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.{Envelope, Geometry}
import com.vividsolutions.jts.geom.prep.{PreparedGeometry, PreparedGeometryFactory}
import org.apache.commons.math3.random.MersenneTwister
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.api.core.v01.population.{PopulationFactory, Person => MatsimPerson, Population => MatsimPopulation}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.{
  HouseholdsFactoryImpl,
  HouseholdsImpl,
  HouseholdsWriterV10,
  Income,
  IncomeImpl,
  Household => MatsimHousehold,
  Households => MatsimHouseholds
}
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.util.Try
import scala.util.control.NonFatal

trait ScenarioGenerator {
  def generate: (MatsimHouseholds, MatsimPopulation)
}

case class PersonWithExtraInfo(person: Models.Person, workDest: PowPumaGeoId, timeLeavingHomeRange: Range)

class SimpleScenarioGenerator(
  val pathToHouseholdFile: String,
  val pathToPopulationFile: String,
  val pathToCTPPFolder: String,
  val pathToPumaShapeFile: String,
  val pathToPowPumaShapeFile: String,
  val pathToBlockGroupShapeFile: String,
  val pathToCongestionLevelDataFile: String,
  val pathToWorkedHours: String,
  val pathToOsmMap: String,
  val randomSeed: Int,
  val offPeakSpeed: Double = 20.5638, // https://inrix.com/scorecard-city/?city=Austin%2C%20TX&index=84
  val defaultValueOfTime: Double = 8.0,
) extends ScenarioGenerator
    with StrictLogging {

  val mapBoundingBox: Envelope = getBoundingBoxOfOsmMap(pathToOsmMap)

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

  override def generate: (MatsimHouseholds, MatsimPopulation) = {
    val pathToCTPPData = PathToData(pathToCTPPFolder)
    val rndWorkDestinationGenerator = new RandomWorkDestinationGenerator(pathToCTPPData, randomSeed)
    val workedDurationGeneratorImpl = new WorkedDurationGeneratorImpl(pathToWorkedHours, randomSeed)

    val households =
      new HouseholdReader(pathToHouseholdFile).read().groupBy(x => x.id).map { case (hhId, xs) => hhId -> xs.head }
    val householdIdToPersons = new PopulationReader(pathToPopulationFile).read().groupBy(x => x.householdId)
    val householdWithPersons = householdIdToPersons.map {
      case (hhId, persons) =>
        val household = households(hhId)
        (household, persons)
    }
    logger.info(s"householdWithPersons: ${householdWithPersons.size}")
    val uniqueStates = households.map(_._2.geoId.state).toSet

    logger.info(s"uniqueStates: ${uniqueStates.size}")

    val powPumaGeoIdMap = getPlaceOfWorkPumaMap(uniqueStates)
    logger.info(s"powPumaGeoIdMap: ${powPumaGeoIdMap.size}")

    val geoIdToHouseholds = households.values.groupBy(x => x.geoId)
    val uniqueGeoIds = geoIdToHouseholds.keySet
    logger.info(s"uniqueGeoIds: ${uniqueGeoIds.size}")

    val pumaIdToMap = getPumaMap
    logger.info(s"pumaIdToMap: ${pumaIdToMap.size}")

    val blockGroupGeoIdToGeom = getBlockGroupMap(uniqueGeoIds)
    logger.info(s"blockGroupGeoIdToGeom: ${blockGroupGeoIdToGeom.size}")

    val blockGroupToPumaMap = ProfilingUtils.timed(
      s"getBlockGroupToPuma for blockGroupGeoIdToGeom ${blockGroupGeoIdToGeom.size} and pumaIdToMap ${pumaIdToMap.size}",
      x => logger.info(x)
    ) {
      getBlockGroupToPuma(blockGroupGeoIdToGeom, pumaIdToMap)
    }
    logger.info(s"blockGroupToPumaMap: ${blockGroupToPumaMap.size}")

    val residenceToWorkplaceFlowGeography: ResidenceToWorkplaceFlowGeography =
      ResidenceToWorkplaceFlowGeography.`PUMA5 To POWPUMA`
    val sourceToTimeLeavingOD =
      new TimeLeavingHomeTableReader(pathToCTPPData, residenceToWorkplaceFlowGeography).read().groupBy(x => x.source)

    val matsimPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val matsimHouseholds = new HouseholdsImpl()
    val householdsFactory = new HouseholdsFactoryImpl()
    val scenarioHouseholdAttributes = matsimHouseholds.getHouseholdAttributes

    var globalPersonId: Int = 0

    val blockGroupGeoIdToHouseholds: Map[BlockGroupGeoId, Iterable[(Models.Household, Seq[PersonWithExtraInfo])]] =
      geoIdToHouseholds.map {
        case (blockGroupGeoId, households) =>
          // TODO We need to bring building density in the future
          val pumaGeoIdOfHousehold = blockGroupToPumaMap(blockGroupGeoId)

          val timeLeavingODPairs = sourceToTimeLeavingOD(pumaGeoIdOfHousehold.asUniqueKey)
          val peopleInHouseholds = households.flatMap(x => householdIdToPersons(x.id))
          logger.info(s"In ${households.size} there are ${peopleInHouseholds.size} people")

          val householdsWithPersonData = households.map { household =>
            val persons = householdWithPersons(household)
            val personWithWorkDestAndTimeLeaving = persons.flatMap { person =>
              rndWorkDestinationGenerator.next(pumaGeoIdOfHousehold, household.income).map { powPumaWorkDest =>
                val foundDests = timeLeavingODPairs.filter(x => x.destination == powPumaWorkDest.asUniqueKey)
                if (foundDests.isEmpty) {
                  logger
                    .info(
                      s"Could not find work destination '${powPumaWorkDest}' in ${timeLeavingODPairs.mkString(" ")}"
                    )
                  PersonWithExtraInfo(
                    person = person,
                    workDest = powPumaWorkDest,
                    timeLeavingHomeRange = defaultTimeLeavingHomeRange
                  )
                } else {
                  val timeLeavingHomeRange =
                    ODSampler.sample(foundDests, rndGen).map(_.attribute).getOrElse(defaultTimeLeavingHomeRange)
                  PersonWithExtraInfo(
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
    // Build work destination to the number of occurrences
    // We need this to be able to generate random work destinations inside geometry.
    val allWorkingDestinations = blockGroupGeoIdToHouseholds.values.flatMap { x =>
      x.flatMap { case (_, xs) => xs.map(_.workDest) }
    }
    val powPumaToOccurrences = allWorkingDestinations.foldLeft(Map[PowPumaGeoId, Int]()) {
      case (acc, c) =>
        val occur = acc.getOrElse(c, 0) + 1
        acc.updated(c, occur)
    }
    logger.info(s"allWorkingDestinations: ${allWorkingDestinations.size}")
    logger.info(s"powPumaToOccurrences: ${powPumaToOccurrences.size}")
    powPumaToOccurrences.foreach {
      case (powPumaGeoId, cnt) =>
        logger.info(s"$powPumaGeoId => $cnt")
    }
    // Generate all work destinations which will be later assigned to people
    val powPumaGeoIdToWorkingLocations = powPumaToOccurrences.par.map {
      case (powPumaGeoId, nWorkingPlaces) =>
        val workingGeos = powPumaGeoIdMap.get(powPumaGeoId) match {
          case Some(geom) =>
            // FIXME
            val nLocations = nWorkingPlaces // if (nWorkingPlaces > 10000) 10000 else nWorkingPlaces
            ProfilingUtils.timed(s"Generate ${nWorkingPlaces} geo points in ${powPumaGeoId}", x => logger.info(x)) {
              RandomPointsInGridGenerator.generate(geom, nLocations)
            }
          case None =>
            logger.warn(s"Can't find ${powPumaGeoId} in `powPumaGeoIdMap`")
            Seq.empty
        }
        powPumaGeoId -> workingGeos
    }.seq

    val nextWorkLocation = mutable.HashMap[PowPumaGeoId, Int]()

    val distances = ArrayBuffer[Double]()

    val blockGroupGeoIdToHouseholdsLocations =
      ProfilingUtils.timed(s"Generate ${households.size} locations", x => logger.info(x)) {
        blockGroupGeoIdToHouseholds.par.map {
          case (blockGroupGeoId, householdsWithPersonData) =>
            val blockGeomOfHousehold = blockGroupGeoIdToGeom(blockGroupGeoId)
            blockGroupGeoId -> RandomPointsInGridGenerator.generate(
              blockGeomOfHousehold.getGeometry,
              householdsWithPersonData.size
            )
        }.seq
      }

    blockGroupGeoIdToHouseholds.foreach {
      case (blockGroupGeoId, householdsWithPersonData) =>
        logger.info(s"BlockGroupId $blockGroupGeoId contains ${householdsWithPersonData.size} households")
        val householdLocation = blockGroupGeoIdToHouseholdsLocations(blockGroupGeoId)
        if (householdLocation.size != householdsWithPersonData.size) {
          logger.warn(
            s"For BlockGroupId $blockGroupGeoId generated ${householdLocation.size} locations, but the number of households is ${householdsWithPersonData.size}"
          )
        }
        householdsWithPersonData.zip(householdLocation).foreach {
          case ((household, personsWithData), wgsHouseholdLocation) =>
            if (mapBoundingBox.contains(wgsHouseholdLocation.getX, wgsHouseholdLocation.getY)) {
              val utmHouseholdCoord = geoUtils.wgs2Utm(wgsHouseholdLocation)

              val (matsimPersons, lastPersonId) = personsWithData.foldLeft((List.empty[MatsimPerson], globalPersonId)) {
                case ((xs, nextPersonId), PersonWithExtraInfo(person, workDestPumaGeoId, timeLeavingHomeRange)) =>
                  val workLocations = powPumaGeoIdToWorkingLocations(workDestPumaGeoId)
                  val offset = nextWorkLocation.getOrElse(workDestPumaGeoId, 0)
                  nextWorkLocation.update(workDestPumaGeoId, offset + 1)
                  workLocations.lift(offset) match {
                    case Some(wgsWorkingLocation) =>
                      if (mapBoundingBox.contains(wgsWorkingLocation.getX, wgsWorkingLocation.getY)) {
                        val matsimPerson =
                          createPerson(
                            household,
                            person,
                            nextPersonId,
                            matsimPopulation.getFactory,
                            matsimPopulation.getPersonAttributes
                          )

                        val plan = PopulationUtils.createPlan(matsimPerson)
                        matsimPerson.addPlan(plan)
                        matsimPerson.setSelectedPlan(plan)

                        // Create Home Activity: end time is when a person leaves a home
                        // Create Leg
                        val leavingHomeActivity =
                          PopulationUtils.createAndAddActivityFromCoord(plan, "Home", utmHouseholdCoord)
                        val timeLeavingHomeSeconds = drawTimeLeavingHome(timeLeavingHomeRange)
                        leavingHomeActivity.setEndTime(timeLeavingHomeSeconds)
                        PopulationUtils.createAndAddLeg(plan, "")

                        val utmWorkingLocation = geoUtils.wgs2Utm(wgsWorkingLocation)
                        val margin = 1.3
                        val distance = geoUtils.distUTMInMeters(utmHouseholdCoord, utmWorkingLocation) * margin
                        distances += distance
                        val travelTime =
                          estimateTravelTime(timeLeavingHomeSeconds, utmHouseholdCoord, utmWorkingLocation, margin)
                        val workStartTime = timeLeavingHomeSeconds + travelTime
                        val workingDuration = workedDurationGeneratorImpl.next(timeLeavingHomeRange)
                        val timeLeavingWork = workStartTime + workingDuration

                        // Create Work Activity: end time is the time when a person leaves a work
                        // Create leg
                        val leavingWorkActivity =
                          PopulationUtils.createAndAddActivityFromCoord(plan, "Work", utmWorkingLocation)
                        leavingWorkActivity.setEndTime(timeLeavingWork)
                        PopulationUtils.createAndAddLeg(plan, "")

                        // Create Home Activity: end time not defined
                        PopulationUtils.createAndAddActivityFromCoord(plan, "Home", utmHouseholdCoord)

                        (matsimPerson :: xs, nextPersonId + 1)
                      } else {
                        logger
                          .info(s"Working location $wgsWorkingLocation does not belong to bounding box $mapBoundingBox")
                        (xs, nextPersonId + 1)
                      }
                    case None =>
                      (xs, nextPersonId + 1)
                  }
              }
              globalPersonId = lastPersonId
              matsimPersons.foreach(matsimPopulation.addPerson)

              val matsimHousehold = createHousehold(household, matsimPersons, householdsFactory)
              matsimHouseholds.addHousehold(matsimHousehold)

              scenarioHouseholdAttributes.putAttribute(household.id, "homecoordx", utmHouseholdCoord.getX)
              scenarioHouseholdAttributes.putAttribute(household.id, "homecoordy", utmHouseholdCoord.getY)
            } else {
              logger.info(s"Household location $wgsHouseholdLocation does not belong to bounding box $mapBoundingBox")
            }
        }
    }

    logger.info(s"Distance stats: ${Statistics(distances)}")
    (matsimHouseholds, matsimPopulation)
  }

  private def createHousehold(
    household: Models.Household,
    matsimPersons: Seq[MatsimPerson],
    householdFactory: HouseholdsFactoryImpl
  ): MatsimHousehold = {
    val h = householdFactory.createHousehold(Id.create[MatsimHousehold](household.id, classOf[MatsimHousehold]))
    h.setIncome(new IncomeImpl(household.income, Income.IncomePeriod.year))
    h.setMemberIds(matsimPersons.map(_.getId).asJava)
    h
  }

  private def createPerson(
    household: Models.Household,
    person: Models.Person,
    personId: Int,
    populationFactory: PopulationFactory,
    personAttributes: ObjectAttributes
  ): MatsimPerson = {
    val sexChar = if (person.gender == Gender.Female) "F" else "M"
    val matsimPerson = populationFactory.createPerson(Id.createPersonId(personId))
    val valueOfTime = PopulationAdjustment.incomeToValueOfTime(household.income).getOrElse(defaultValueOfTime)
    personAttributes.putAttribute(personId.toString, "householdId", person.householdId)
    personAttributes.putAttribute(personId.toString, "age", person.age)
    personAttributes.putAttribute(personId.toString, "valueOfTime", valueOfTime)
    personAttributes.putAttribute(personId.toString, "sex", sexChar)
    matsimPerson.getAttributes.putAttribute("sex", sexChar)
    matsimPerson.getAttributes.putAttribute("age", person.age)
    matsimPerson
  }

  private def getPumaMap: Map[PumaGeoId, PreparedGeometry] = {
    val dataStore = new ShapefileDataStore(new File(pathToPumaShapeFile).toURI.toURL)
    try {
      val fe = dataStore.getFeatureSource.getFeatures.features()
      try {
        val it = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fe.hasNext
          override def next(): SimpleFeature = fe.next()
        }
        val tractGeoIdToGeom = it.map { feature =>
          val state = feature.getAttribute("STATEFP10").toString
          val puma = feature.getAttribute("PUMACE10").toString
          val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
          PumaGeoId(state, puma) -> geom
        }.toMap
        tractGeoIdToGeom
      } finally {
        Try(fe.close())
      }
    } finally {
      Try(dataStore.dispose())
    }
  }

  private def getPlaceOfWorkPumaMap(uniqueStates: Set[String]): Map[PowPumaGeoId, Geometry] = {
    val dataStore = new ShapefileDataStore(new File(pathToPowPumaShapeFile).toURI.toURL)
    try {
      val fe = dataStore.getFeatureSource.getFeatures.features()
      val destinationCoordSystem = CRS.decode("EPSG:4326", true)
      val mathTransform =
        CRS.findMathTransform(dataStore.getSchema.getCoordinateReferenceSystem, destinationCoordSystem, true)
      try {
        val it = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fe.hasNext
          override def next(): SimpleFeature = fe.next()
        }
        val tractGeoIdToGeom = it
          .filter { feature =>
            val state = feature.getAttribute("PWSTATE").toString
            uniqueStates.contains(state)
          }
          .map { feature =>
            val state = feature.getAttribute("PWSTATE").toString
            val puma = feature.getAttribute("PWPUMA").toString
            val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
            val wgsGeom = JTS.transform(geom.getGeometry, mathTransform)
            PowPumaGeoId(state, puma) -> wgsGeom
          }
          .toMap
        tractGeoIdToGeom
      } finally {
        Try(fe.close())
      }
    } finally {
      Try(dataStore.dispose())
    }
  }

  private def getBlockGroupMap(uniqueGeoIds: Set[BlockGroupGeoId]): Map[BlockGroupGeoId, PreparedGeometry] = {
    val dataStore = new ShapefileDataStore(new File(pathToBlockGroupShapeFile).toURI.toURL)
    try {
      val fe = dataStore.getFeatureSource.getFeatures.features()
      try {
        val it = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fe.hasNext
          override def next(): SimpleFeature = fe.next()
        }
        val tazGeoIdToGeom = it
          .filter { feature =>
            val state = feature.getAttribute("STATEFP").toString
            val county = feature.getAttribute("COUNTYFP").toString
            val tract = feature.getAttribute("TRACTCE").toString
            val blockGroup = feature.getAttribute("BLKGRPCE").toString
            val shouldConsider = uniqueGeoIds.contains(
              BlockGroupGeoId(state = state, county = county, tract = tract, blockGroup = blockGroup)
            )
            shouldConsider
          }
          .map { feature =>
            val state = feature.getAttribute("STATEFP").toString
            val county = feature.getAttribute("COUNTYFP").toString
            val tract = feature.getAttribute("TRACTCE").toString
            val blockGroup = feature.getAttribute("BLKGRPCE").toString
            val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
            BlockGroupGeoId(state = state, county = county, tract = tract, blockGroup = blockGroup) -> geom
          }
          .toMap
        tazGeoIdToGeom
      } finally {
        Try(fe.close())
      }
    } finally {
      Try(dataStore.dispose())
    }
  }

  def getBlockGroupToPuma(
    blockGroupGeoIdToGeom: Map[BlockGroupGeoId, PreparedGeometry],
    pumaGeoIdToGeom: Map[PumaGeoId, PreparedGeometry],
  ): Map[BlockGroupGeoId, PumaGeoId] = {
    // TODO: This can be easily parallelize (very dummy improvement, in case if there is nothing better)
    val blockGroupToPuma = blockGroupGeoIdToGeom
      .flatMap {
        case (blockGroupGeoId, blockGroupGeom) =>
          // Intersect with all and get the best by the covered area
          val allIntersections = pumaGeoIdToGeom.map {
            case (pumaGeoId, pumaGeom) =>
              val intersection = blockGroupGeom.getGeometry.intersection(pumaGeom.getGeometry)
              (intersection, blockGroupGeoId, pumaGeoId)
          }
          val best = if (allIntersections.nonEmpty) Some(allIntersections.maxBy(x => x._1.getArea)) else None
          best
      }
      .map {
        case (_, blockGroupGeoId, pumaGeoId) =>
          blockGroupGeoId -> pumaGeoId
      }
      .toMap
    blockGroupToPuma
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

object SimpleScenarioGenerator {

  def main(args: Array[String]): Unit = {
    require(
      args.size == 9,
      "Expecting four arguments: first one is the path to household CSV file, the second one is the path to population CSV file, the third argument is the path to Census Tract shape file, the fourth argument is the path to Census TAZ shape file"
    )
    val pathToHouseholdFile = args(0)
    val pathToPopulationFile = args(1)
    val pathToCTPPFolder = args(2)
    val pathToPumaShapeFile = args(3)
    val pathToPowPumaShapeFile = args(4)
    val pathToBlockGroupShapeFile = args(5)
    val pathToCongestionLevelDataFile = args(6)
    val pathToWorkedHours = args(7)
    val pathToOsmMap = args(8)

    /*
    Args:
    "C:\repos\synthpop\demos\household_TX_Travis County.csv"
    "C:\repos\synthpop\demos\people_TX_Travis County.csv"
    "D:\Work\beam\Austin\2012-2016 CTPP documentation\tx\48"
    "D:\Work\beam\Austin\Census\tl_2014_48_puma10\tl_2014_48_puma10.shp"
    "C:\Users\User\Downloads\ipums_migpuma_pwpuma_2010\ipums_migpuma_pwpuma_2010.shp"
    "D:\Work\beam\Austin\Census\tl_2019_48_bg\tl_2019_48_bg.shp"
    "D:\Work\beam\Austin\CongestionLevel_Austin.csv"
    "D:\Work\beam\Austin\work_activities_all_us.csv"
    "D:\Work\beam\Austin\texas-a-bit-bigger.osm.pbf"
     * */

    val gen =
      new SimpleScenarioGenerator(
        pathToHouseholdFile,
        pathToPopulationFile,
        pathToCTPPFolder,
        pathToPumaShapeFile,
        pathToPowPumaShapeFile,
        pathToBlockGroupShapeFile,
        pathToCongestionLevelDataFile,
        pathToWorkedHours,
        pathToOsmMap,
        42
      )

    val (households, population) = gen.generate
    val scenario = ScenarioUtils.createMutableScenario(ConfigUtils.createConfig())
    scenario.setHouseholds(households)
    scenario.setPopulation(population)

    new HouseholdsWriterV10(households).writeFile("households.xml.gz")
    new PopulationWriter(population).write("population.xml.gz")
    new ObjectAttributesXmlWriter(households.getHouseholdAttributes).writeFile("householdAttributes.xml.gz")
    new ObjectAttributesXmlWriter(population.getPersonAttributes).writeFile(s"populationAttributes.xml.gz")

    try {
      PopulationCsvWriter.toCsv(scenario, "population.csv.gz")
    } catch {
      case NonFatal(ex) =>
        println(s"Can't write population: ${ex}")
    }

    try {
      HouseholdsCsvWriter.toCsv(scenario, "household.csv.gz")
    } catch {
      case NonFatal(ex) =>
        println(s"Can't write households: ${ex}")
    }

    try {
      PlansCsvWriter.toCsv(scenario, "plan.csv.gz")
    } catch {
      case NonFatal(ex) =>
        println(s"Can't write plans: ${ex}")
    }
  }
}
