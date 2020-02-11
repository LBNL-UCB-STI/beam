package beam.utils.data.synthpop

import java.io.File

import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.RandomPointsInGridGenerator
import beam.utils.data.synthpop.models.Models
import beam.utils.data.synthpop.models.Models.{Gender, GeoId}
import com.typesafe.scalalogging.StrictLogging
import com.vividsolutions.jts.geom.prep.{PreparedGeometry, PreparedGeometryFactory}
import com.vividsolutions.jts.geom.{Geometry, Point}
import org.geotools.data.shapefile.ShapefileDataStore
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
  val pathToShapeFile: String,
  val defaultValueOfTime: Double = 8.0
) extends ScenarioGenerator
    with StrictLogging {

  private val geoUtils: GeoUtils = new beam.sim.common.GeoUtils {
    // TODO: Is it truth for all cases? Check the coverage https://epsg.io/26910
    // WGS84 bounds:
    //-172.54 23.81
    //-47.74 86.46
    override def localCRS: String = "epsg:26910"
  }

  override def generate: (MatsimHouseholds, MatsimPopulation) = {
    val households =
      new HouseholdReader(pathToHouseholdFile).read().groupBy(x => x.id).map { case (hhId, xs) => hhId -> xs.head }
    val householdIdToPersons = new PopulationReader(pathToPopulationFile).read().groupBy(x => x.householdId)
    val householdWithPersons = householdIdToPersons.map {
      case (hhId, persons) =>
        val household = households(hhId)
        (household, persons)
    }
    logger.info(s"householdWithPersons: ${householdWithPersons.size}")

    val geoIdToHouseholds = households.values.groupBy(x => x.geoId)
    val uniqueGeoIds = geoIdToHouseholds.keySet.map(_.asUniqueKey)
    logger.info(s"uniqueGeoIds: ${uniqueGeoIds.size}")

    val geoIdToGeom = getGeoIdToGeomMap(uniqueGeoIds)
    logger.info(s"geoIdToGeom: ${geoIdToGeom.size}")

    val matsimPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val matsimHouseholds = new HouseholdsImpl()
    val householdsFactory = new HouseholdsFactoryImpl()
    val scenarioHouseholdAttributes = matsimHouseholds.getHouseholdAttributes

    var globalPersonId: Int = 0

    geoIdToHouseholds.foreach {
      case (geoId, households) =>
        val nLocationsToGenerate = households.size
        val geom = geoIdToGeom(geoId)
        val centroid = geom.getGeometry.getCentroid
        val householdLocation = RandomPointsInGridGenerator.generate(geom.getGeometry, nLocationsToGenerate)
        require(householdLocation.size == nLocationsToGenerate)
        households.zip(householdLocation).foreach {
          case (household, wgsLocation) =>
            val persons = householdWithPersons(household)

            val (matsimPersons, lastPersonId) = persons.foldLeft((List.empty[MatsimPerson], globalPersonId)) {
              case ((xs, nextPersonId), person) =>
                val matsimPerson =
                  createPerson(
                    household,
                    person,
                    nextPersonId,
                    matsimPopulation.getFactory,
                    matsimPopulation.getPersonAttributes
                  )
                (matsimPerson :: xs, nextPersonId + 1)
            }
            globalPersonId = lastPersonId
            matsimPersons.foreach(matsimPopulation.addPerson)

            val matsimHousehold = createHousehold(household, matsimPersons, householdsFactory)
            matsimHouseholds.addHousehold(matsimHousehold)

            val utmCoord = geoUtils.wgs2Utm(wgsLocation)
            scenarioHouseholdAttributes.putAttribute(household.id, "homecoordx", utmCoord.getX)
            scenarioHouseholdAttributes.putAttribute(household.id, "homecoordy", utmCoord.getY)
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
    val valueOfTime = PopulationAdjustment.IncomeToValueOfTime(household.income).getOrElse(defaultValueOfTime)
    personAttributes.putAttribute(personId.toString, "householdId", person.householdId)
    personAttributes.putAttribute(personId.toString, "age", person.age)
    personAttributes.putAttribute(personId.toString, "valueOfTime", valueOfTime)
    personAttributes.putAttribute(personId.toString, "sex", sexChar)
    matsimPerson.getAttributes.putAttribute("sex", sexChar)
    matsimPerson.getAttributes.putAttribute("age", person.age)
    matsimPerson
  }

  private def getGeoIdToGeomMap(uniqueGeoIds: Set[String]): Map[GeoId, PreparedGeometry] = {
    val dataStore = new ShapefileDataStore(new File(pathToShapeFile).toURI.toURL)
    try {
      val fe = dataStore.getFeatureSource.getFeatures.features()
      try {
        val it = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fe.hasNext
          override def next(): SimpleFeature = fe.next()
        }
        val geoIdToGeom = it
          .filter { feature =>
            val geoIdStr = feature.getAttribute("GEOID").toString
            val shouldConsider = uniqueGeoIds.contains(geoIdStr)
            shouldConsider
          }
          .map { feature =>
            val state = feature.getAttribute("STATEFP").toString
            val county = feature.getAttribute("COUNTYFP").toString
            val tract = feature.getAttribute("TRACTCE").toString
            val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
            GeoId(state, county, tract) -> geom
          }
          .toMap
        geoIdToGeom
      } finally {
        Try(fe.close())
      }
    } finally {
      Try(dataStore.dispose())
    }
  }
}

object SimpleScenarioGenerator {

  def main(args: Array[String]): Unit = {
    require(
      args.size == 3,
      "Expecting two arguments: first one is the path to household CSV file, the second one is the path to population CSV file"
    )
    val pathToHouseholdFile = args(0)
    val pathToPopulationFile = args(1)
    val pathToShapeFile = args(2)

    val gen = new SimpleScenarioGenerator(pathToHouseholdFile, pathToPopulationFile, pathToShapeFile)

    gen.generate

  }
}
