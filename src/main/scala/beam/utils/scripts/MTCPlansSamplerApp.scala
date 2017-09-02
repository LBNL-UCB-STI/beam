package beam.utils.scripts

import java.util
import java.util.stream.Collectors

import com.vividsolutions.jts.geom.{Coordinate, Geometry}
import org.apache.log4j.Logger
import org.matsim.api.core.v01.population.{Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id, Scenario}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.population.PopulationUtils
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.collections.QuadTree
import org.matsim.core.utils.geometry.CoordUtils
import org.matsim.core.utils.gis.ShapeFileReader
import org.matsim.households.Household
import org.opengis.feature.simple.SimpleFeature

import scala.io.Source
import scala.util.{Failure, Random, Success, Try}


case class SynthHousehold(numPersons: Integer, cars: Integer, coord: Coord)

trait HasXY{
  def getX:Double
  def getY:Double
}

object HasXY {
  implicit class PlanXY(plan:Plan) extends HasXY{
    val coord:Coord =  PopulationUtils.getFirstActivity(plan).getCoord
    override def getX: Double = coord.getX
    override def getY: Double = coord.getY
  }

  implicit class SynthHouseHoldXY(synthHousehold: SynthHousehold) extends HasXY{
    override def getX: Double = synthHousehold.coord.getY
    override def getY: Double = synthHousehold.coord.getX
  }
}


case class QuadTreeExtent(minx: Double, miny: Double, maxx: Double, maxy: Double)

object QuadTreeBuilder {

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
          val ca: Array[Coordinate] = g.getEnvelope.getCoordinates
          minX = Math.min(minX, ca(0).x)
          minY = Math.min(minY, ca(0).y)
          maxX = Math.max(maxX, ca(2).x)
          maxY = Math.max(maxY, ca(2).y)
        case _ =>
      }
    }
    QuadTreeExtent(minX, minY, maxX, maxY)
  }

  def buildQuadTree[T<:HasXY](aoiShapeFileLoc: String, els: Vector[T]): QuadTree[T] = {
    val qte = quadTreeExtentFromShapeFile(aoiShapeFileLoc)
    val qt: QuadTree[T] = new QuadTree[T](qte.minx, qte.miny, qte.maxx, qte.maxy)
    for (p <- els) {
      qt.put(p.getX, p.getY, p)
    }
    qt
  }
}



object SynthHouseholdParser {

  private val hhNumIdx: Int = 0
  private val carNumIdx: Int = 1
  private val homeCoordXIdx: Int = 2
  private val homeCoordYIdx: Int = 3

  def parseFile(synthFileName: String): Try[Vector[SynthHousehold]] = {
    import beam.utils.FileUtils.using
    Try {
      val households = using(Source.fromFile(synthFileName)) { source =>
        for {line <- source.getLines
             sl <- line.split(",")}
          yield SynthHousehold(sl(hhNumIdx).toInt, sl(carNumIdx).toInt,
            new Coord(sl(homeCoordXIdx).toDouble, sl(homeCoordYIdx).toDouble))
      }
      households.toVector
    }
  }

}

object MTCPlansSampler {
  import HasXY._
  private val logger = Logger.getLogger("MTCPlansSampler")

  private var planQt: Option[QuadTree[Plan]] = None
  val conf: Config = ConfigUtils.createConfig()

  val sc: MutableScenario = ScenarioUtils.createMutableScenario(conf)

  private val synthPop = Vector[SynthHousehold]()

  private val pop = Vector[Person]()

  def init(args: Array[String]): Unit = {

    conf.plans.setInputFile(args(0))
    sc.setLocked()
    ScenarioUtils.loadScenario(sc)
    pop :+ sc.getPopulation.getPersons.values()

    SynthHouseholdParser.parseFile(args(3)) match {
      case Success(hh) => this.synthPop :+ hh
      case Failure(s)=> println(s"Failed, message is: $s")
    }
    val plans = pop.map(_.getPlans.get(0))
    planQt = Some(QuadTreeBuilder.buildQuadTree(args(1), plans))
  }

  def getClosestNPlans(spCoord:Coord, n:Int): Vector[Plan]={
    val planOrdering: Ordering[Plan] = Ordering.by(p=>CoordUtils.calcEuclideanDistance(spCoord,new Coord(p.getX,p.getY)))
    val closestPlan = getClosestPlan(spCoord)
    val col = Vector(closestPlan)
    var radius = CoordUtils.calcEuclideanDistance(spCoord,new Coord(closestPlan.getX,closestPlan.getY))

    while(col.size<n-1){
      radius+=1
      col :+ planQt.get.getDisk(spCoord.getX,spCoord.getY,radius)
    }
    col.sorted(planOrdering)
  }

  def getClosestPlan(spCoord:Coord):Plan={
    planQt.get.getClosest(spCoord.getX,spCoord.getY)
  }

  def run(): Unit = {
    val newPop = PopulationUtils.createPopulation(ConfigUtils.createConfig())

    val newHH = sc.getHouseholds
    val x = for{sp <- synthPop
                                                n = sp.numPersons
                                                closestPlans:Array[Plan] <- getClosestNPlans(sp.coord,n)
                                                selectedHomePlan = closestPlans(Random.nextInt(n))
                                                householdId = Id.create(selectedHomePlan.getPerson.getId, classOf[Household])
                                                household = newHH.getFactory.createHousehold(householdId)
        } yield (household, selectedHomePlan, closestPlans)
  }

}

object MTCPlansSamplerApp extends App {

  val sampler = MTCPlansSampler
  sampler.init(args)
  sampler.run()
}


