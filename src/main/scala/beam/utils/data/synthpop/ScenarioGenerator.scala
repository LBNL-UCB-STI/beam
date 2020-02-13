package beam.utils.data.synthpop

import java.io.File

import beam.sim.common.GeoUtils
import beam.sim.population.PopulationAdjustment
import beam.taz.RandomPointsInGridGenerator
import beam.utils.data.synthpop.models.Models
import beam.utils.data.synthpop.models.Models.{Gender, TazGeoId, TractGeoId}
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
  val pathToTractShapeFile: String,
  val pathToTazShapeFile: String,
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
    val uniqueGeoIds = geoIdToHouseholds.keySet
    logger.info(s"uniqueGeoIds: ${uniqueGeoIds.size}")

    val tractGeoIdToGeom = getTractMap(uniqueGeoIds)
    logger.info(s"tractGeoIdToGeom: ${tractGeoIdToGeom.size}")

    val tazGeoIdToGeom = getTazMap(uniqueGeoIds)
    logger.info(s"tazGeoIdToGeom: ${tazGeoIdToGeom.size}")

    val tractToTazes = getCensusTractToTaz(tractGeoIdToGeom, tazGeoIdToGeom)
    logger.info(s"tractToTazes: ${tractToTazes.size}")

    val matsimPopulation = PopulationUtils.createPopulation(ConfigUtils.createConfig())
    val matsimHouseholds = new HouseholdsImpl()
    val householdsFactory = new HouseholdsFactoryImpl()
    val scenarioHouseholdAttributes = matsimHouseholds.getHouseholdAttributes

    var globalPersonId: Int = 0

    geoIdToHouseholds.foreach {
      case (geoId, households) =>
        val nLocationsToGenerate = households.size
        val geom = tractGeoIdToGeom(geoId)
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

  private def getTractMap(uniqueGeoIds: Set[TractGeoId]): Map[TractGeoId, PreparedGeometry] = {
    val dataStore = new ShapefileDataStore(new File(pathToTractShapeFile).toURI.toURL)
    try {
      val fe = dataStore.getFeatureSource.getFeatures.features()
      try {
        val it = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fe.hasNext
          override def next(): SimpleFeature = fe.next()
        }
        val tractGeoIdToGeom = it
          .filter { feature =>
            val state = feature.getAttribute("STATEFP").toString
            val county = feature.getAttribute("COUNTYFP").toString
            val tract = feature.getAttribute("TRACTCE").toString
            val shouldConsider = uniqueGeoIds.contains(TractGeoId(state, county, tract))
            shouldConsider
          }
          .map { feature =>
            val state = feature.getAttribute("STATEFP").toString
            val county = feature.getAttribute("COUNTYFP").toString
            val tract = feature.getAttribute("TRACTCE").toString
            val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
            TractGeoId(state, county, tract) -> geom
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

  private def getTazMap(uniqueGeoIds: Set[TractGeoId]): Map[TazGeoId, PreparedGeometry] = {
    val considerOnlyStateAndCounty = uniqueGeoIds.map(_.copy(tract = ""))
    val dataStore = new ShapefileDataStore(new File(pathToTazShapeFile).toURI.toURL)
    try {
      val fe = dataStore.getFeatureSource.getFeatures.features()
      try {
        val it = new Iterator[SimpleFeature] {
          override def hasNext: Boolean = fe.hasNext
          override def next(): SimpleFeature = fe.next()
        }
        val tazGeoIdToGeom = it
          .filter { feature =>
            val state = feature.getAttribute("STATEFP10").toString
            val county = feature.getAttribute("COUNTYFP10").toString
            val shouldConsider = considerOnlyStateAndCounty.contains(TractGeoId(state, county, ""))
            shouldConsider
          }
          .map { feature =>
            val state = feature.getAttribute("STATEFP10").toString
            val county = feature.getAttribute("COUNTYFP10").toString
            val taz = feature.getAttribute("TAZCE10").toString
            val geom = PreparedGeometryFactory.prepare(feature.getDefaultGeometry.asInstanceOf[Geometry])
            TazGeoId(state, county, taz) -> geom
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

  def getCensusTractToTaz(
    tractGeoIdToGeom: Map[TractGeoId, PreparedGeometry],
    tazGeoIdToGeom: Map[TazGeoId, PreparedGeometry]
  ): Map[TractGeoId, Vector[(TazGeoId, PreparedGeometry)]] = {
    // TODO: This can be easily parallelized (very dummy improvement, in case if there is nothing better)
    val result = tractGeoIdToGeom.map {
      case (tractGeoId, tractGeom) =>
        val insideTract = tazGeoIdToGeom.filter {
          case (tazGeoId, tazGeom) =>
            // TODO: Not sure this is best way, but better than contains for weird looking polygons, like tract with full id: 48 453 001775
            // https://imgur.com/a/6aedkUo
            // You can see that the red Census Tract and inside it there are multiple TAZ, but some of TAZs are partially outsize of census tract
            tractGeom.intersects(tazGeom.getGeometry)
        }.toVector
        tractGeoId -> insideTract
    }
    result.filter { case (k, v) => v.nonEmpty }
  }
}

object SimpleScenarioGenerator {

  def main(args: Array[String]): Unit = {
    require(
      args.size == 4,
      "Expecting four arguments: first one is the path to household CSV file, the second one is the path to population CSV file, the third argument is the path to Census Tract shape file, the fourth argument is the path to Census TAZ shape file"
    )
    val pathToHouseholdFile = args(0)
    val pathToPopulationFile = args(1)
    val pathToTractShapeFile = args(2)
    val pathToTazShapeFile = args(3)

    val gen =
      new SimpleScenarioGenerator(pathToHouseholdFile, pathToPopulationFile, pathToTractShapeFile, pathToTazShapeFile)

    gen.generate

  }
}
