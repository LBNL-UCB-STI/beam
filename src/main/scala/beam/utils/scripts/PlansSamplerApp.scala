package beam.utils.scripts

import java.util

import beam.utils.gis.Plans2Shapefile
import beam.utils.scripts.HouseholdAttrib.{HomeCoordX, HomeCoordY, HousingType}
import beam.utils.scripts.PopulationAttrib.Rank
import com.vividsolutions.jts.geom.{Envelope, Geometry}
import org.apache.log4j.Logger
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
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.core.utils.misc.Counter
import org.matsim.households.{Household, Households, HouseholdsFactory, HouseholdsWriterV10}
import org.matsim.pt2matsim.tools.{CoordTools, NetworkTools}
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.matsim.vehicles.{Vehicle, VehicleUtils, VehicleWriterV1, Vehicles}
import org.opengis.feature.simple.SimpleFeature

import scala.collection.{JavaConverters, immutable}
import scala.io.Source
import scala.util.Random


case class SynthHousehold(householdId: Id[Household], numPersons: Integer, cars: Integer, coord: Coord)

import enumeratum.EnumEntry._
import enumeratum._


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

  private def quadTreeExtentFromShapeFile(aoiShapeFileName: String): QuadTreeExtent = {
    var minX: Double = Double.MaxValue
    var maxX: Double = Double.MinValue
    var minY: Double = Double.MaxValue
    var maxY: Double = Double.MinValue
    val sfr: ShapeFileReader = new ShapeFileReader
    sfr.readFileAndInitialize(aoiShapeFileName)
    val features: util.Collection[SimpleFeature] = sfr.getFeatureSet

    import scala.collection.JavaConversions._
    for (f <- features) {
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

  def buildQuadTree[T: HasXY](aoiShapeFileLoc: String, els: Vector[T]): QuadTree[T] = {
    val ev = implicitly[HasXY[T]]
    val qte = quadTreeExtentFromShapeFile(aoiShapeFileLoc)
    val qt: QuadTree[T] = new QuadTree[T](qte.minx, qte.miny, qte.maxx, qte.maxy)
    for (p <- els) {
      qt.put(ev.getX(p), ev.getY(p), p)
    }
    qt
  }
}


object SynthHouseholdParser {

  import HasXY.wgs2Utm


  private val hhIdIdx: Int = 0
  private val hhNumIdx: Int = 1
  private val carNumIdx: Int = 2
  private val homeCoordXIdx: Int = 3
  private val homeCoordYIdx: Int = 4

  def parseFile(synthFileName: String): Vector[SynthHousehold] = {
    var res = Vector[SynthHousehold]()
    for (line <- Source.fromFile(synthFileName, "utf-8").getLines) {
      val sl = line.split(",")
      val pt = wgs2Utm.transform(new Coord(sl(homeCoordXIdx).toDouble, sl(homeCoordYIdx).toDouble))

      val householdId = Id.create(sl(hhIdIdx), classOf[Household])
      val numCars = sl(carNumIdx).toDouble.toInt
      val numPeople = sl(hhNumIdx).toDouble.toInt
      res ++= Vector(SynthHousehold(householdId, numPeople, numCars, pt))
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
  val newHHFac: HouseholdsFactory = newHH.getFactory
  val newHHAttributes: ObjectAttributes = sc.getHouseholds.getHouseholdAttributes

  private var synthPop = Vector[SynthHousehold]()

  private var pop = Vector[Person]()
  var outDir: String = ""

  def init(args: Array[String]): Unit = {

    conf.plans.setInputFile(args(0))
    conf.network.setInputFile(args(2))
    conf.vehicles.setVehiclesFile(args(4))
    sc.setLocked()
    ScenarioUtils.loadScenario(sc)
    pop ++= scala.collection.JavaConverters.mapAsScalaMap(sc.getPopulation.getPersons).values.toVector
    synthPop ++= SynthHouseholdParser.parseFile(args(3))

    val plans = pop.map(_.getPlans.get(0))

    planQt = Some(QuadTreeBuilder.buildQuadTree(args(1), plans))
    outDir = args(5)
  }

  def snapPlanActivityLocsToNearestLink(plan:Plan): Plan ={

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

    var radius = CoordUtils.calcEuclideanDistance(spCoord, PopulationUtils.getFirstActivity(closestPlan).getCoord)

    while (col.size < n ) {
      radius += 1
      val candidates = JavaConverters.collectionAsScalaIterable(planQt.get.getDisk(spCoord.getX, spCoord.getY, radius))
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

  def run(): Unit = {


    // Init vehicle type (easier to do here)
    val defaultVehicleType = JavaConverters.collectionAsScalaIterable(sc.getVehicles.getVehicleTypes.values()).head
    newVehicles.addVehicleType(defaultVehicleType)


    Random.shuffle(synthPop).take((0.0001 * synthPop.size).toInt).toStream.foreach(sh => {

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
        newPopAttributes.putAttribute(newPersonId.toString, Rank.entryName, Random.nextInt(sh.numPersons))

        // Create a new plan for household member based on selected plan of first person
        val newPlan = PopulationUtils.createPlan(newPerson)
        newPerson.addPlan(newPlan)
        PopulationUtils.copyFromTo(plan, newPlan)

        homePlan match {
          case None =>
            homePlan = Some(newPlan)
            val homeActs = JavaConverters.collectionAsScalaIterable(Plans2Shapefile
              .getActivities(newPlan.getPlanElements, new StageActivityTypesImpl("Home")))
            val homeCoord = homeActs.head.getCoord
            newHHAttributes.putAttribute(hhId.toString, HomeCoordX.entryName, homeCoord.getX)
            newHHAttributes.putAttribute(hhId.toString, HomeCoordY.entryName, homeCoord.getY)
            newHHAttributes.putAttribute(hhId.toString, HousingType.entryName, "House")
            snapPlanActivityLocsToNearestLink(newPlan)
          case Some(hp) =>
            val firstAct = PopulationUtils.getFirstActivity(hp)
            val firstActCoord = firstAct.getCoord
            val homeActs = JavaConverters.collectionAsScalaIterable(Plans2Shapefile
              .getActivities(newPlan.getPlanElements, new StageActivityTypesImpl("Home")))
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
    new ObjectAttributesXmlWriter(newPopAttributes).writeFile(s"$outDir/populationAttributes.xml.gz")

  }

}

/**
  * Inputs
  * [0] Raw plans input filename
  * [1] AOI shapefile
  * [2] Network input filename
  * [3] Synthetic person filename
  * [4] Default vehicle type(s)
  * [5] Output directory
  */
object PlansSamplerApp extends App {
  val sampler = PlansSampler
  sampler.init(args)
  sampler.run()
}


