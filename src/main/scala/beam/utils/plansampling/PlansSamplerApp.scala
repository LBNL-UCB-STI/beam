package beam.utils.plansampling

import java.util

import beam.utils.gis.Plans2Shapefile
import beam.utils.plansampling.HouseholdAttrib.{HomeCoordX, HomeCoordY, HousingType}
import beam.utils.plansampling.PopulationAttrib.Rank
import beam.utils.scripts.PopulationWriterCSV
import com.vividsolutions.jts.geom.{Coordinate, Envelope, Geometry, GeometryCollection, GeometryFactory, Point}
import enumeratum.EnumEntry._
import enumeratum._
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.population.{Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.population.{PersonUtils, PopulationUtils}
import org.matsim.core.router.StageActivityTypesImpl
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.io.IOUtils
import org.matsim.core.utils.misc.Counter
import org.matsim.households._
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.matsim.vehicles.{Vehicle, VehicleUtils, VehicleWriterV1, Vehicles}
import org.matsim.households.Income.IncomePeriod.year

import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.crs.CoordinateReferenceSystem
import scala.collection.mutable.ListBuffer
import scala.collection.{immutable, mutable, AbstractSeq, JavaConverters}
import scala.collection.generic.CanBuildFrom
import scala.util.Random

case class SynthHousehold(
  householdId: Id[Household],
  numPersons: Int,
  vehicles: Int,
  hhIncome: Double,
  tract: Int,
  coord: Coord,
  var individuals: Array[SynthIndividual]
) {

  def addIndividual(individual: SynthIndividual): Unit = {
    individuals ++= Array(individual)
  }
}

case class SynthIndividual(indId: Id[Person], sex: Int, age: Int, valueOfTime: Double)

class SynthHouseholdParser(wgsConverter: WGSConverter) {

  import SynthHouseholdParser._
  import scala.util.control.Breaks._

  /**
    * Parses the synthetic households file.
    *
    * @param synthFileName : synthetic households filename
    * @return the [[Vector]] of [[SynthHousehold]]s
    */
  def parseFile(synthFileName: String): Vector[SynthHousehold] = {
    val resHHMap = scala.collection.mutable.Map[String, SynthHousehold]()

    IOUtils
      .getBufferedReader(synthFileName)
      .lines()
      .forEach(line => {
        val row = line.split(",")
        val hhIdStr = row(hhIdIdx)
        resHHMap.get(hhIdStr) match {
          case Some(hh: SynthHousehold) => hh.addIndividual(parseIndividual(row))
          case None                     => resHHMap += (hhIdStr -> parseHousehold(row, hhIdStr))
        }
      })

    resHHMap.values.toVector
  }

  def parseIndividual(row: Array[String]): SynthIndividual = {
    SynthIndividual(
      Id.createPersonId(row(indIdIdx)),
      row(indSexIdx).toInt,
      row(indAgeIdx).toInt,
      row(indValTime).toDouble
    )
  }

  def parseHousehold(row: Array[String], hhIdStr: String): SynthHousehold = {
    SynthHousehold(
      Id.create(hhIdStr, classOf[Household]),
      row(carNumIdx).toInt,
      row(hhNumIdx).toInt,
      row(hhIncomeIdx).toDouble,
      row(hhTractIdx).toInt,
      wgsConverter.wgs2Utm.transform(
        new Coord(row(homeCoordXIdx).toDouble, row(homeCoordYIdx).toDouble)
      ),
      Array(parseIndividual(row))
    )
  }
}

object SynthHouseholdParser {
  private val indIdIdx: Int = 0
  private val hhIdIdx: Int = 1
  private val hhNumIdx: Int = 2
  private val carNumIdx: Int = 3
  private val hhIncomeIdx: Int = 4
  private val indIncomeIdx: Int = 5
  private val indSexIdx: Int = 6
  private val indAgeIdx = 7
  private val hhTractIdx = 8
  private val homeCoordXIdx: Int = 9
  private val homeCoordYIdx: Int = 10
  private val indValTime: Int = 11
}

sealed trait HouseholdAttrib extends EnumEntry

object HouseholdAttrib extends Enum[HouseholdAttrib] {

  override def values: immutable.IndexedSeq[HouseholdAttrib] = findValues

  case object HomeCoordX extends HouseholdAttrib with LowerCamelcase

  case object HomeCoordY extends HouseholdAttrib with LowerCamelcase

  case object HousingType extends HouseholdAttrib with LowerCamelcase

}

sealed trait PopulationAttrib extends EnumEntry

object PopulationAttrib extends Enum[PopulationAttrib] {

  override def values: immutable.IndexedSeq[PopulationAttrib] = findValues

  case object Rank extends PopulationAttrib with LowerCamelcase

  case object AvailableModes extends PopulationAttrib with LowerCamelcase

}

sealed trait ModeAvailTypes extends EnumEntry

object ModeAvailTypes extends Enum[ModeAvailTypes] {

  override def values: immutable.IndexedSeq[ModeAvailTypes] = findValues

  //  case object Sometimes extends ModeAvailTypes with Lowercase

  case object Always extends ModeAvailTypes with Lowercase

  case object Never extends ModeAvailTypes with Lowercase

}

trait HasXY[T] {
  def getX(t: T): Double

  def getY(t: T): Double
}

object HasXY {

  implicit object PlanXY extends HasXY[Plan] {
    override def getX(p: Plan): Double =
      PopulationUtils.getFirstActivity(p).getCoord.getX

    override def getY(p: Plan): Double =
      PopulationUtils.getFirstActivity(p).getCoord.getY
  }

  implicit object SynthHouseHoldXY extends HasXY[SynthHousehold] {
    override def getX(sh: SynthHousehold): Double = sh.coord.getY

    override def getY(sh: SynthHousehold): Double = sh.coord.getX
  }

}

case class WGSConverter(sourceCRS: String, targetCRS: String) {

  val wgs2Utm: GeotoolsTransformation =
    new GeotoolsTransformation(sourceCRS, targetCRS)

  def wgs2Utm(envelope: Envelope): Envelope = {
    val ll: Coord =
      wgs2Utm.transform(new Coord(envelope.getMinX, envelope.getMinY))
    val ur: Coord =
      wgs2Utm.transform(new Coord(envelope.getMaxX, envelope.getMaxY))
    new Envelope(ll.getX, ur.getX, ll.getY, ur.getY)
  }
}

case class QuadTreeExtent(minx: Double, miny: Double, maxx: Double, maxy: Double)

class QuadTreeBuilder(wgsConverter: WGSConverter) {

  private def quadTreeExtentFromShapeFile(
    features: util.Collection[SimpleFeature]
  ): QuadTreeExtent = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue

    import scala.collection.JavaConverters._
    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          val ca = wgsConverter.wgs2Utm(g.getEnvelope.getEnvelopeInternal)
          minX = Math.min(minX, ca.getMinX)
          minY = Math.min(minY, ca.getMinY)
          maxX = Math.max(maxX, ca.getMaxX)
          maxY = Math.max(maxY, ca.getMaxY)
        case _ =>
      }
    }
    QuadTreeExtent(minX, minY, maxX, maxY)
  }

  // Returns a single geometry that is the union of all the polgyons in a shapefile
  def geometryUnionFromShapefile(
    features: util.Collection[SimpleFeature],
    sourceCRS: CoordinateReferenceSystem
  ): Geometry = {

    import scala.collection.JavaConverters._
    val targetCRS = CRS.decode(wgsConverter.targetCRS)
    val transform = CRS.findMathTransform(sourceCRS, targetCRS, false)
    val outGeoms = new util.ArrayList[Geometry]()
    // Get the polygons and add them to the output list
    for (f <- features.asScala) {
      f.getDefaultGeometry match {
        case g: Geometry =>
          //          val ca = wgs2Utm(g.getEnvelope.getEnvelopeInternal)
          val gt = JTS.transform(g, transform) // transformed geometry
          outGeoms.add(gt)
      }
    }
    val gF = new GeometryFactory()
    val gC = new GeometryCollection(outGeoms.asScala.toArray, gF)
    val union = gC.union()
    union
  }

  // This version parses all activity locations and only keeps agents who have all activities w/ in the bounds
  def buildQuadTree[T: HasXY](
    aoiShapeFileLoc: util.Collection[SimpleFeature],
    sourceCRS: CoordinateReferenceSystem,
    pop: Vector[Person]
  ): QuadTree[T] = {
    val ev = implicitly[HasXY[T]]

    val qte = quadTreeExtentFromShapeFile(aoiShapeFileLoc)
    val qt: QuadTree[T] =
      new QuadTree[T](qte.minx, qte.miny, qte.maxx, qte.maxy)
    // Get the shapefile Envelope
    val aoi = geometryUnionFromShapefile(aoiShapeFileLoc, sourceCRS)
    // loop through all activities and check if each is in the bounds

    for (person <- pop.par) {
      val pplan = person.getPlans.get(0) // First and only plan
      val activities = PopulationUtils.getActivities(pplan, null)

      // If any activities outside of bounding box, skip this person
      var allIn = true
      activities.forEach(act => {
        val coord = act.getCoord
        val point: Point =
          MGC.xy2Point(coord.getX, coord.getY)
        if (!aoi.contains(point)) {
          allIn = false
        }
      })
      if (allIn) {
        val first = PopulationUtils.getFirstActivity(pplan)
        val coord = first.getCoord
        qt.put(coord.getX, coord.getY, pplan.asInstanceOf[T])
      }
    }
    qt
  }
}

object PlansSampler {

  import HasXY._

  val availableModeString: String = "available-modes"
  val counter: Counter = new Counter("[" + this.getClass.getSimpleName + "] created household # ")

  private var planQt: Option[QuadTree[Plan]] = None
  var wgsConverter: Option[WGSConverter] = None
  val conf: Config = ConfigUtils.createConfig()

  private val sc: MutableScenario = ScenarioUtils.createMutableScenario(conf)
  private val newPop: Population =
    PopulationUtils.createPopulation(ConfigUtils.createConfig())
  val newPopAttributes: ObjectAttributes = newPop.getPersonAttributes
  val newVehicles: Vehicles = VehicleUtils.createVehiclesContainer()
  val newHHFac: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  val newHH: HouseholdsImpl = new HouseholdsImpl()
  val newHHAttributes: ObjectAttributes = newHH.getHouseholdAttributes
  val shapeFileReader: ShapeFileReader = new ShapeFileReader

  val modeAllocator: AvailableModeUtils.AllowAllModes =
    new AvailableModeUtils.AllowAllModes

  private var synthHouseholds = Vector[SynthHousehold]()

  private var pop = Vector[Person]()
  var outDir: String = ""
  var sampleNumber: Int = 0

  def init(args: Array[String]): Unit = {
    conf.plans.setInputFile(args(0))
    conf.network.setInputFile(args(2))
    conf.vehicles.setVehiclesFile(args(4))
    sampleNumber = args(5).toInt
    sc.setLocked()
    ScenarioUtils.loadScenario(sc)
    shapeFileReader.readFileAndInitialize(args(1))
    wgsConverter = Some(WGSConverter(args(7), args(8)))
    pop ++= scala.collection.JavaConverters
      .mapAsScalaMap(sc.getPopulation.getPersons)
      .values
      .toVector

    synthHouseholds ++=
      filterSynthHouseholds(
        new SynthHouseholdParser(wgsConverter.get).parseFile(args(3)),
        shapeFileReader.getFeatureSet,
        shapeFileReader.getCoordinateSystem
      )

    planQt = Some(
      new QuadTreeBuilder(wgsConverter.get)
        .buildQuadTree(shapeFileReader.getFeatureSet, shapeFileReader.getCoordinateSystem, pop)
    )

    outDir = args(6)
  }

  private def snapPlanActivityLocsToNearestLink(plan: Plan): Plan = {

    val allActivities =
      PopulationUtils.getActivities(plan, new StageActivityTypesImpl(""))

    allActivities.forEach(x => {
      val nearestLink = NetworkUtils.getNearestLink(sc.getNetwork, x.getCoord)
      x.setCoord(nearestLink.getCoord)
    })
    plan
  }

  private def getClosestNPlans(spCoord: Coord, n: Int): Set[Plan] = {
    val closestPlan = getClosestPlan(spCoord)
    var col = Set(closestPlan)

    var radius = CoordUtils.calcEuclideanDistance(
      spCoord,
      PopulationUtils.getFirstActivity(closestPlan).getCoord
    )

    while (col.size < n) {
      radius += 1
      val candidates = JavaConverters.collectionAsScalaIterable(
        planQt.get.getDisk(spCoord.getX, spCoord.getY, radius)
      )
      for (plan <- candidates) {
        if (!col.contains(plan)) {
          col ++= Vector(plan)
        }
      }
    }
    col
  }

  def getClosestPlan(spCoord: Coord): Plan = {
    planQt.get.getClosest(spCoord.getX, spCoord.getY)
  }

  private def filterSynthHouseholds(
    synthHouseholds: Vector[SynthHousehold],
    aoiFeatures: util.Collection[SimpleFeature],
    sourceCRS: CoordinateReferenceSystem
  ): Vector[SynthHousehold] = {

    val aoi: Geometry = new QuadTreeBuilder(wgsConverter.get)
      .geometryUnionFromShapefile(aoiFeatures, sourceCRS)

    synthHouseholds
      .filter(hh => aoi.contains(MGC.coord2Point(hh.coord)))
      .take(sampleNumber)
  }

  def addModeExclusions(person: Person): AnyRef = {

    val permissibleModes: Iterable[String] =
      JavaConverters.collectionAsScalaIterable(
        modeAllocator.getPermissibleModes(person.getSelectedPlan)
      )

    val availableModes = permissibleModes
      .fold("") { (addend, modeString) =>
        addend.concat(modeString.toLowerCase() + ",")
      }
      .stripSuffix(",")

    newPopAttributes.putAttribute(person.getId.toString, availableModeString, availableModes)
  }

  def run(): Unit = {

    val carVehicleType =
      JavaConverters
        .collectionAsScalaIterable(sc.getVehicles.getVehicleTypes.values())
        .head
    newVehicles.addVehicleType(carVehicleType)
    synthHouseholds.foreach(sh => {
      val numPersons = sh.individuals.length
      val N = if (numPersons * 2 > 0) {
        numPersons * 2
      } else {
        1
      }

      val closestPlans: Set[Plan] = getClosestNPlans(sh.coord, N)

      val selectedPlans = Random.shuffle(closestPlans).take(numPersons)

      val hhId = sh.householdId
      val spHH = newHHFac.createHousehold(hhId)

      // Add household to households and increment counter now
      newHH.getHouseholds.put(hhId, spHH)

      // Set hh income
      spHH.setIncome(newHHFac.createIncome(sh.hhIncome, year))

      counter.incCounter()

      spHH.setIncome(newHHFac.createIncome(sh.hhIncome, Income.IncomePeriod.year))
      // Create and add car identifiers
      (0 to sh.vehicles).foreach(x => {
        val vehicleId = Id.createVehicleId(s"${counter.getCounter}-$x")
        val vehicle: Vehicle =
          VehicleUtils.getFactory.createVehicle(vehicleId, carVehicleType)
        newVehicles.addVehicle(vehicle)
        spHH.getVehicleIds.add(vehicleId)
      })

      var homePlan: Option[Plan] = None

      var ranks: immutable.Seq[Int] = 0 to sh.individuals.length
      ranks = Random.shuffle(ranks)

      for ((plan, idx) <- selectedPlans.zipWithIndex) {
        val synthPerson = sh.individuals.toVector(idx)
        val newPersonId = synthPerson.indId
        val newPerson = newPop.getFactory.createPerson(newPersonId)
        newPop.addPerson(newPerson)
        spHH.getMemberIds.add(newPersonId)
        newPopAttributes
          .putAttribute(newPersonId.toString, Rank.entryName, ranks(idx))

        // Create a new plan for household member based on selected plan of first person
        val newPlan = PopulationUtils.createPlan(newPerson)
        newPerson.addPlan(newPlan)
        PopulationUtils.copyFromTo(plan, newPlan)

        homePlan match {
          case None =>
            homePlan = Some(newPlan)
            val homeActs = JavaConverters.collectionAsScalaIterable(
              Plans2Shapefile
                .getActivities(newPlan.getPlanElements, new StageActivityTypesImpl("Home"))
            )
            val homeCoord = homeActs.head.getCoord
            newHHAttributes.putAttribute(hhId.toString, HomeCoordX.entryName, homeCoord.getX)
            newHHAttributes.putAttribute(hhId.toString, HomeCoordY.entryName, homeCoord.getY)
            newHHAttributes.putAttribute(hhId.toString, HousingType.entryName, "House")
            snapPlanActivityLocsToNearestLink(newPlan)

          case Some(hp) =>
            val firstAct = PopulationUtils.getFirstActivity(hp)
            val firstActCoord = firstAct.getCoord
            val homeActs = JavaConverters.collectionAsScalaIterable(
              Plans2Shapefile
                .getActivities(newPlan.getPlanElements, new StageActivityTypesImpl("Home"))
            )
            for (act <- homeActs) {
              act.setCoord(firstActCoord)
            }
            snapPlanActivityLocsToNearestLink(newPlan)
        }

        PersonUtils.setAge(newPerson, synthPerson.age)
        val sex = if (synthPerson.sex == 0) { "M" } else { "F" }
        // TODO: Include non-binary gender if data available
        PersonUtils.setSex(newPerson, sex)
        newPopAttributes
          .putAttribute(newPerson.getId.toString, "valueOfTime", synthPerson.valueOfTime)
        addModeExclusions(newPerson)
      }

    })

    counter.printCounter()
    counter.reset()

    new HouseholdsWriterV10(newHH).writeFile(s"$outDir/households.xml.gz")
    new PopulationWriter(newPop).write(s"$outDir/population.xml.gz")
    PopulationWriterCSV(newPop).write(s"$outDir/population.csv.gz")
    new VehicleWriterV1(newVehicles).writeFile(s"$outDir/vehicles.xml.gz")
    new ObjectAttributesXmlWriter(newHHAttributes)
      .writeFile(s"$outDir/householdAttributes.xml.gz")
    new ObjectAttributesXmlWriter(newPopAttributes)
      .writeFile(s"$outDir/populationAttributes.xml.gz")

  }

}

/**
  * This script is designed to create input data for BEAM. It expects the following inputs [provided in order of
  * command-line args]:
  *
  * [0] Raw plans input filename
  * [1] Input AOI shapefile
  * [2] Network input filename
  * [3] Synthetic person input filename
  * [4] Default vehicle type(s) input filename
  * [5] Number of persons to sample (e.g., 1k, 5k, etc.)
  * [6] Output directory
  * [7] Target CRS
  *
  * Run from directly from CLI with, for example:
  *
  * $> gradle :execute -PmainClass=beam.utils.plansampling.PlansSamplerApp
  * -PappArgs="['production/application-sfbay/population.xml.gz', 'production/application-sfbay/shape/bayarea_county_dissolve_4326.shp',
  * 'production/application-sfbay/physsim-network.xml', 'test/input/sf-light/ind_X_hh_out.csv.gz',
  * 'production/application-sfbay/vehicles.xml.gz', '413187', production/application-sfbay/samples', 'epsg:4326', 'epsg:26910']"
  */
object PlansSamplerApp extends App {
  val sampler = PlansSampler
  sampler.init(args)
  sampler.run()
}
