package beam.utils.scripts

import java.util

import beam.utils.gis.Plans2Shapefile
import beam.utils.scripts.HouseholdAttrib.{HomeCoordX, HomeCoordY, HousingType}
import beam.utils.scripts.PopulationAttrib.Rank
import com.vividsolutions.jts.geom.{
  Coordinate,
  Envelope,
  Geometry,
  GeometryCollection,
  GeometryFactory,
  Point
}
import enumeratum.EnumEntry._
import enumeratum._
import org.apache.log4j.Logger
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.population.{Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.population.PopulationUtils
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.router.StageActivityTypesImpl
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.core.utils.geometry.geotools.MGC
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.misc.Counter
import org.matsim.households._
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.matsim.vehicles.{Vehicle, VehicleUtils, VehicleWriterV1, Vehicles}
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.crs.CoordinateReferenceSystem

import scala.collection.mutable.ListBuffer
import scala.collection.{immutable, JavaConverters}
import scala.io.Source
import scala.util.Random

case class SynthHousehold(
  householdId: Id[Household],
  numPersons: Int,
  cars: Int,
  hhIncome: Double,
  coord: Coord
)

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

}

trait HasXY[T] {
  def getX(t: T): Double

  def getY(t: T): Double
}

object HasXY {
  val wgs2Utm: GeotoolsTransformation = new GeotoolsTransformation("EPSG:4326", "EPSG:26910")

  def wgs2Utm(envelope: Envelope): Envelope = {
    val ll: Coord = wgs2Utm.transform(new Coord(envelope.getMinX, envelope.getMinY))
    val ur: Coord = wgs2Utm.transform(new Coord(envelope.getMaxX, envelope.getMaxY))
    new Envelope(ll.getX, ur.getX, ll.getY, ur.getY)
  }

  implicit object PlanXY extends HasXY[Plan] {
    override def getX(p: Plan): Double = PopulationUtils.getFirstActivity(p).getCoord.getX

    override def getY(p: Plan): Double = PopulationUtils.getFirstActivity(p).getCoord.getY
  }

  implicit object SynthHouseHoldXY extends HasXY[SynthHousehold] {
    override def getX(sh: SynthHousehold): Double = sh.coord.getY

    override def getY(sh: SynthHousehold): Double = sh.coord.getX
  }

}

case class QuadTreeExtent(minx: Double, miny: Double, maxx: Double, maxy: Double)

object QuadTreeBuilder {

  import HasXY.wgs2Utm

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
          val ca = wgs2Utm(g.getEnvelope.getEnvelopeInternal)
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
    val targetCRS = CRS.decode("EPSG:26910")
    val transform = CRS.findMathTransform(sourceCRS, targetCRS, false)
    var outGeoms = new util.ArrayList[Geometry]()
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
    val qt: QuadTree[T] = new QuadTree[T](qte.minx, qte.miny, qte.maxx, qte.maxy)
    // Get the shapefile Envelope
    val aoi = geometryUnionFromShapefile(aoiShapeFileLoc, sourceCRS)
    // loop through all activities and check if each is in the bounds
    for (person <- pop) {
      val pplan = person.getPlans.get(0) // First and only plan
      //      val elements = JavaConverters.collectionAsScalaIterable(pplan.getPlanElements())
      val activities = PopulationUtils.getActivities(pplan, null)
      //      val plans = JavaConverters.collectionAsScalaIterable(person.getPlans())   //.iterator();
      //      while (plans.hasNext()){
      //        val plan = plans.next();

      // If any activities outside of bounding box, skip this person
      var allIn = true
      activities.forEach(act => {
        val coord = act.getCoord
        val gF = new GeometryFactory()
        val vsCoord = Array(new Coordinate(coord.getX, coord.getY))
        val point = new Point(gF.getCoordinateSequenceFactory.create(vsCoord), gF)
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

object SynthHouseholdParser {

  import HasXY.wgs2Utm

  private val hhIdIdx: Int = 0
  private val hhNumIdx: Int = 1
  private val carNumIdx: Int = 2
  private val hhIncomeIdx: Int = 3
  private val homeCoordXIdx: Int = 4
  private val homeCoordYIdx: Int = 5

  /**
    *
    * @param synthFileName : synthetic households filename
    * @return the [[Vector]] of [[SynthHousehold]]s
    */
  def parseFile(synthFileName: String): Vector[SynthHousehold] = {
    var res = Vector[SynthHousehold]()
    for (line <- Source.fromFile(synthFileName, "utf-8").getLines) {
      val sl = line.split(",")
      val pt = wgs2Utm.transform(new Coord(sl(homeCoordXIdx).toDouble, sl(homeCoordYIdx).toDouble))

      val householdId = Id.create(sl(hhIdIdx), classOf[Household])
      val numCars = sl(carNumIdx).toInt
      val numPeople = sl(hhNumIdx).toInt
      val hhIncome = sl(hhIncomeIdx).toInt
      res ++= Vector(SynthHousehold(householdId, numPeople, numCars, hhIncome, pt))
    }
    res
  }

}

object PlansSampler {

  import HasXY._

  val counter: Counter = new Counter("[" + this.getClass.getSimpleName + "] created household # ")
  private val logger = Logger.getLogger("PlansSampler")

  private var planQt: Option[QuadTree[Plan]] = None
  val conf: Config = ConfigUtils.createConfig()

  val sc: MutableScenario = ScenarioUtils.createMutableScenario(conf)
  val newPop: Population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
  val newPopAttributes: ObjectAttributes = newPop.getPersonAttributes
  val newVehicles: Vehicles = VehicleUtils.createVehiclesContainer()
  val newHH: Households = sc.getHouseholds
  val newHHFac: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  val newHHAttributes: ObjectAttributes = newHH.getHouseholdAttributes
  val shapeFileReader: ShapeFileReader = new ShapeFileReader

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

    pop ++= scala.collection.JavaConverters
      .mapAsScalaMap(sc.getPopulation.getPersons)
      .values
      .toVector

    synthHouseholds ++=
      filterSynthHouseholds(
        SynthHouseholdParser.parseFile(args(3)),
        shapeFileReader.getFeatureSet,
        shapeFileReader.getCoordinateSystem
      )

    planQt = Some(
      QuadTreeBuilder
        .buildQuadTree(shapeFileReader.getFeatureSet, shapeFileReader.getCoordinateSystem, pop)
    )

    outDir = args(6)
  }

  def snapPlanActivityLocsToNearestLink(plan: Plan): Plan = {

    val allActivities = PopulationUtils.getActivities(plan, new StageActivityTypesImpl(""))

    allActivities.forEach(x => {
      val nearestLink = NetworkUtils.getNearestLink(sc.getNetwork, x.getCoord)
      x.setCoord(nearestLink.getCoord)
    })
    plan
  }

  def getClosestNPlans(spCoord: Coord, n: Int): Set[Plan] = {
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
    val aoi: Geometry = QuadTreeBuilder.geometryUnionFromShapefile(aoiFeatures, sourceCRS)
    var totalPersonNumber = 0
    var idx = 0
    val popSize = synthHouseholds.size
    val shuffledHouseholds = Random.shuffle(synthHouseholds) // Randomize here
    var ret = ListBuffer[SynthHousehold]()
    while (totalPersonNumber < popSize && totalPersonNumber < sampleNumber) {
      val hh: SynthHousehold = shuffledHouseholds(idx)
      if (aoi.contains(MGC.coord2Point(hh.coord))) {
        ret += hh
        totalPersonNumber += hh.numPersons
      }
      idx += 1
    }
    ret.toVector
  }

  def run(): Unit = {

    val defaultVehicleType =
      JavaConverters.collectionAsScalaIterable(sc.getVehicles.getVehicleTypes.values()).head
    newVehicles.addVehicleType(defaultVehicleType)
    synthHouseholds foreach (sh => {

      val N = if (sh.numPersons * 2 > 0) {
        sh.numPersons * 2
      } else {
        1
      }

      val closestPlans: Set[Plan] = getClosestNPlans(sh.coord, N)

      val selectedPlans = Random.shuffle(closestPlans).take(sh.numPersons)

      val hhId = Id.create(counter.getCounter, classOf[Household])
      val spHH = newHHFac.createHousehold(hhId)

      // Add household to households and increment counter now
      newHH.getHouseholds.put(hhId, spHH)
      counter.incCounter()
      spHH.setIncome(newHHFac.createIncome(sh.hhIncome, Income.IncomePeriod.year))
      // Create and add car identifiers
      (0 to sh.cars).foreach(x => {
        val vehicleId = Id.createVehicleId(s"${counter.getCounter}-$x")
        val vehicle: Vehicle = VehicleUtils.getFactory.createVehicle(vehicleId, defaultVehicleType)
        newVehicles.addVehicle(vehicle)
        spHH.getVehicleIds.add(vehicleId)
      })

      var homePlan: Option[Plan] = None
      for ((plan, idx) <- selectedPlans.zipWithIndex) {

        val newPersonId = Id.createPersonId(s"${counter.getCounter}-$idx")
        val newPerson = newPop.getFactory.createPerson(newPersonId)
        newPop.addPerson(newPerson)
        spHH.getMemberIds.add(newPersonId)
        newPopAttributes
          .putAttribute(newPersonId.toString, Rank.entryName, Random.nextInt(sh.numPersons))

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
      }
    })
    counter.printCounter()
    counter.reset()

    new HouseholdsWriterV10(newHH).writeFile(s"$outDir/households.xml.gz")
    //    new PopulationWriter(newPop, sc.getNetwork, 0.01).write(s"$outDir/synthPlans0.01.xml.gz")
    //    new PopulationWriter(newPop, sc.getNetwork, 0.1).write(s"$outDir/synthPlans0.1.xml.gz")
    new PopulationWriter(newPop).write(s"$outDir/population.xml.gz")
    PopulationWriterCSV(newPop).write(s"$outDir/population.csv.gz")
    new VehicleWriterV1(newVehicles).writeFile(s"$outDir/vehicles.xml.gz")
    new ObjectAttributesXmlWriter(newHHAttributes).writeFile(s"$outDir/householdAttributes.xml.gz")
    new ObjectAttributesXmlWriter(newPopAttributes)
      .writeFile(s"$outDir/populationAttributes.xml.gz")

  }

}

/**
  * Inputs
  * [0] Raw plans input filename
  * [1] Input AOI shapefile
  * [2] Network input filename
  * [3] Synthetic person input filename
  * [4] Default vehicle type(s) input filename
  * [5] Number of persons to sample (e.g., 1k, 5k, etc.)
  * [6] Output directory
  */
object PlansSamplerApp extends App {
  val sampler = PlansSampler
  sampler.init(args)
  sampler.run()
}
