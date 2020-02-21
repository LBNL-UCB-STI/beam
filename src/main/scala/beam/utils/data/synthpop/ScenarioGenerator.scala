package beam.utils.data.synthpop

import java.io.File

import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.RandomPointsInGridGenerator
import beam.utils.ProfilingUtils
import beam.utils.data.ctpp.models.ResidenceToWorkplaceFlowGeography
import beam.utils.data.ctpp.readers.BaseTableReader.PathToData
import beam.utils.data.ctpp.readers.flow.TimeLeavingHomeTableReader
import beam.utils.data.synthpop.models.Models
import beam.utils.data.synthpop.models.Models.{BlockGroupGeoId, Gender, PowPumaGeoId, PumaGeoId}
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.Geometry
import com.vividsolutions.jts.geom.prep.{PreparedGeometry, PreparedGeometryFactory}
import org.apache.commons.math3.random.MersenneTwister
import org.geotools.data.shapefile.ShapefileDataStore
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{PopulationFactory, Person => MatsimPerson, Population => MatsimPopulation}
import org.matsim.core.config.ConfigUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{
  HouseholdsFactoryImpl,
  HouseholdsImpl,
  Income,
  IncomeImpl,
  Household => MatsimHousehold,
  Households => MatsimHouseholds
}
import org.matsim.utils.objectattributes.ObjectAttributes
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters._
import scala.util.Try

trait ScenarioGenerator {
  def generate: (MatsimHouseholds, MatsimPopulation)
}

class SimpleScenarioGenerator(
  val pathToHouseholdFile: String,
  val pathToPopulationFile: String,
  val pathToCTPPFolder: String,
  val pathToPumaShapeFile: String,
  val pathToPowPumaShapeFile: String,
  val pathToBlockGroupShapeFile: String,
  val pathToWorkedHours: String,
  val randomSeed: Int,
  val defaultValueOfTime: Double = 8.0
) extends ScenarioGenerator
    with StrictLogging {

  private val rndGen: MersenneTwister = new MersenneTwister(randomSeed) // Random.org

  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    // TODO: Is it truth for all cases? Check the coverage https://epsg.io/26910
    // WGS84 bounds:
    //-172.54 23.81
    //-47.74 86.46
    override def localCRS: String = "epsg:26910"
  }

  private val defaultTimeLeavingHomeRange: Range = Range(6 * 3600, 7 * 3600)

  def drawTimeLeavingHome(timeLeavingHomeRange: Range): Double = {
    // Randomly pick a number between [start, end]
    val howMany = timeLeavingHomeRange.end - timeLeavingHomeRange.start + 1
    timeLeavingHomeRange.start + rndGen.nextInt(howMany)
  }

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

    geoIdToHouseholds.foreach {
      case (blockGroupGeoId, households) =>
        // TODO We need to bring building density in the future
        val pumaGeoIdOfHousehold = blockGroupToPumaMap(blockGroupGeoId)

        val timeLeavingODPairs = sourceToTimeLeavingOD(pumaGeoIdOfHousehold.asUniqueKey)
        val peopleInHouseholds = households.flatMap(x => householdIdToPersons(x.id))
        logger.info(s"In ${households.size} there are ${peopleInHouseholds.size} people")

        val householdWithPersonData = households.map { household =>
          val persons = householdWithPersons(household)
          val personWithWorkDestAndTimeLeaving = persons.flatMap { person =>
            rndWorkDestinationGenerator.next(pumaGeoIdOfHousehold, household.income).map { powPumaWorkDest =>
              val foundDests = timeLeavingODPairs.filter(x => x.destination == powPumaWorkDest.asUniqueKey)
              if (foundDests.isEmpty) {
                logger
                  .info(s"Could not find work destination '${powPumaWorkDest}' in ${timeLeavingODPairs.mkString(" ")}")
                (person, powPumaWorkDest, defaultTimeLeavingHomeRange)
              } else {
                val timeLeavingHomeRange =
                  ODSampler.sample(foundDests, rndGen).map(_.attribute).getOrElse(defaultTimeLeavingHomeRange)
                (person, powPumaWorkDest, timeLeavingHomeRange)
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
        logger.info(s"householdWithPersonData: ${householdWithPersonData.size}")
        val blockGeomOfHousehold = blockGroupGeoIdToGeom(blockGroupGeoId)
        val nLocationsToGenerate = households.size
        val householdLocation =
          RandomPointsInGridGenerator.generate(blockGeomOfHousehold.getGeometry, nLocationsToGenerate)
        require(householdLocation.size == nLocationsToGenerate)
        householdWithPersonData.zip(householdLocation).foreach {
          case ((household, personsWithData), wgsHouseholdLocation) =>
            val utmHouseholdCoord = geoUtils.wgs2Utm(wgsHouseholdLocation)

            val (matsimPersons, lastPersonId) = personsWithData.foldLeft((List.empty[MatsimPerson], globalPersonId)) {
              case ((xs, nextPersonId), (person, workDestPumaGeoId, timeLeavingHomeRange)) =>
                // TODO Map `workDist` to actual geo location
                // TODO Create plan from `timeLeavingHomeRange`
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
                val leavingHomeActivity = PopulationUtils.createAndAddActivityFromCoord(plan, "Home", utmHouseholdCoord)
                leavingHomeActivity.setEndTime(drawTimeLeavingHome(timeLeavingHomeRange))
                PopulationUtils.createAndAddLeg(plan, "")

                val workDestGeo = powPumaGeoIdMap.get(workDestPumaGeoId)
                logger.info(s"workDestGeo: ${workDestGeo}")

                // Create Work Activity: end time is the time when a person leaves a work
                // Create leg

                // Create Home Activity: end time not defined

                (matsimPerson :: xs, nextPersonId + 1)
            }
            globalPersonId = lastPersonId
            matsimPersons.foreach(matsimPopulation.addPerson)

            val matsimHousehold = createHousehold(household, matsimPersons, householdsFactory)
            matsimHouseholds.addHousehold(matsimHousehold)

            scenarioHouseholdAttributes.putAttribute(household.id, "homecoordx", utmHouseholdCoord.getX)
            scenarioHouseholdAttributes.putAttribute(household.id, "homecoordy", utmHouseholdCoord.getY)
        }
    }
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
}

object SimpleScenarioGenerator {

  def main(args: Array[String]): Unit = {
    require(
      args.size == 7,
      "Expecting four arguments: first one is the path to household CSV file, the second one is the path to population CSV file, the third argument is the path to Census Tract shape file, the fourth argument is the path to Census TAZ shape file"
    )
    val pathToHouseholdFile = args(0)
    val pathToPopulationFile = args(1)
    val pathToCTPPFolder = args(2)
    val pathToPumaShapeFile = args(3)
    val pathToPowPumaShapeFile = args(4)
    val pathToBlockGroupShapeFile = args(5)
    val pathToWorkedHours = args(6)

    val gen =
      new SimpleScenarioGenerator(
        pathToHouseholdFile,
        pathToPopulationFile,
        pathToCTPPFolder,
        pathToPumaShapeFile,
        pathToPowPumaShapeFile,
        pathToBlockGroupShapeFile,
        pathToWorkedHours,
        42
      )

    gen.generate

  }

}
