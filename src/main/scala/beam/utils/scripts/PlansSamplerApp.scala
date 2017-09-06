package beam.utils.scripts

import java.util
import java.util.UUID

import beam.utils.gis.Plans2Shapefile
import com.vividsolutions.jts.geom.{Envelope, Geometry}
import org.apache.log4j.Logger
import org.matsim.api.core.v01.population.{Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.{Config, ConfigUtils}
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
import org.opengis.feature.simple.SimpleFeature

import scala.collection.JavaConverters
import scala.io.Source
import scala.util.Random


case class SynthHousehold(householdId: Id[Household], numPersons: Integer, cars: Integer, coord: Coord)


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

  private val logger = Logger.getLogger("PlansSampler")

  private var planQt: Option[QuadTree[Plan]] = None
  val conf: Config = ConfigUtils.createConfig()

  val sc: MutableScenario = ScenarioUtils.createMutableScenario(conf)
  val newPop: Population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
  val hh: Households = sc.getHouseholds
  val hhFac: HouseholdsFactory = hh.getFactory

  val newHH: Households = sc.getHouseholds

  private var synthPop = Vector[SynthHousehold]()

  private var pop = Vector[Person]()
  var outDir: String = ""

  def init(args: Array[String]): Unit = {

    conf.plans.setInputFile(args(0))
    sc.setLocked()
    ScenarioUtils.loadScenario(sc)
    pop ++= scala.collection.JavaConverters.mapAsScalaMap(sc.getPopulation.getPersons).values.toVector

    synthPop ++= SynthHouseholdParser.parseFile(args(2))

    val plans = pop.map(_.getPlans.get(0))

    planQt = Some(QuadTreeBuilder.buildQuadTree(args(1), plans))
    outDir = args(3)
  }

  def getClosestNPlans(spCoord: Coord, n: Int): Vector[Plan] = {
    val planOrdering: Ordering[Plan] = Ordering.by(p => CoordUtils.calcEuclideanDistance(spCoord, PopulationUtils.getFirstActivity(p).getCoord))
    val closestPlan = getClosestPlan(spCoord)
    var col = Vector(closestPlan)

    var radius = CoordUtils.calcEuclideanDistance(spCoord, PopulationUtils.getFirstActivity(closestPlan).getCoord)

    while (col.size < n - 1) {
      radius += 1
      val candidates = JavaConverters.collectionAsScalaIterable(planQt.get.getDisk(spCoord.getX, spCoord.getY, radius))
      for (plan <- candidates) {
        if (!col.contains(plan)) {
          col ++= Vector(plan)
        }
      }
    }
    col.sorted(planOrdering)
  }

  def getClosestPlan(spCoord: Coord): Plan = {
    planQt.get.getClosest(spCoord.getX, spCoord.getY)
  }

  def run(): Unit = {


    val counter: Counter = new Counter("[" + this.getClass.getSimpleName + "] created household # ")

    Random.shuffle(synthPop).take((0.01*synthPop.size).toInt).toStream.foreach(sh => {

      val N = if (sh.numPersons * 2 > 0) {
        sh.numPersons * 2
      } else {
        1
      }

      val closestPlans = getClosestNPlans(sh.coord, N)

      val selectedPlans = (0 to sh.numPersons).map(x => {
        val x = Random.nextInt(N) - 1
        closestPlans(if (x < 0) {
          0
        } else x)
      })

      val hhId = sh.householdId
      val spHH = hhFac.createHousehold(hhId)
      // Add household to households and increment counter now
      newHH.getHouseholds.put(hhId, spHH)
      counter.incCounter()

      var homePlan: Option[Plan] = None
      for (plan <- selectedPlans) {

        var newPersonId = Id.createPersonId(s"${UUID.randomUUID()}")
        val newPerson = newPop.getFactory.createPerson(newPersonId)
        newPop.addPerson(newPerson)
        spHH.getMemberIds.add(newPersonId)

        // Create a new plan for household member based on selected plan of first person
        val newPlan = PopulationUtils.createPlan(newPerson)
        newPerson.addPlan(newPlan)
        PopulationUtils.copyFromTo(plan, newPlan)

        homePlan match {
          case None =>
            homePlan = Some(newPlan)
          case Some(hp) =>
            val firstAct = PopulationUtils.getFirstActivity(hp)
            val firstActCoord = firstAct.getCoord
            val homeActs = JavaConverters.collectionAsScalaIterable(Plans2Shapefile
              .getActivities(newPlan.getPlanElements, new StageActivityTypesImpl("Home")))
            for (act <- homeActs) {
              act.setCoord(firstActCoord)
            }
        }

        // Create and add car identifiers
        (1 to sh.cars).foreach(x => spHH.getVehicleIds.add(Id.createVehicleId(s"car-$hhId-$x")))

      }
    })
    counter.printCounter()
    counter.reset()


    new HouseholdsWriterV10(newHH).writeFile(s"$outDir/synthHouseHolds.xml.gz")
//    new PopulationWriter(newPop, sc.getNetwork, 0.01).write(s"$outDir/synthPlans0.01.xml.gz")
//    new PopulationWriter(newPop, sc.getNetwork, 0.1).write(s"$outDir/synthPlans0.1.xml.gz")
    new PopulationWriter(newPop).write(s"$outDir/synthPlansFull.xml.gz")

  }

}

object PlansSamplerApp extends App {
  val sampler = PlansSampler
  sampler.init(args)
  sampler.run()
}


