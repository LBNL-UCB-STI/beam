package beam.utils.plan.sampling

import java.util

import beam.router.Modes.BeamMode.CAR
import beam.utils.matsim_conversion.{MatsimConversionTool, ShapeUtils}
import beam.utils.plan.sampling.HouseholdAttrib.{HomeCoordX, HomeCoordY, HousingType}
import beam.utils.plan.sampling.PopulationAttrib.Rank
import beam.utils.scripts.PopulationWriterCSV
import com.vividsolutions.jts.geom.{Envelope, Geometry, GeometryCollection, GeometryFactory, Point}
import enumeratum.EnumEntry._
import enumeratum._
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.Pair
import org.geotools.geometry.jts.JTS
import org.geotools.referencing.CRS
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
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
import org.matsim.households.Income.IncomePeriod.year
import org.matsim.households._
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.matsim.vehicles.{Vehicle, VehicleUtils, VehicleWriterV1, Vehicles}
import org.opengis.feature.simple.SimpleFeature
import org.opengis.referencing.crs.CoordinateReferenceSystem

import scala.collection.JavaConverters._
import scala.collection.{immutable, mutable, JavaConverters}
import scala.util.control.Breaks._
import scala.util.{Random, Try}

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

case class SynthIndividual(indId: Id[Person], sex: Int, age: Int, valueOfTime: Double, income: Double)

class SynthHouseholdParser(geoConverter: GeoConverter) {

  import SynthHouseholdParser._

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

  def parseSex(raw_sex: String): Int = {
    if (raw_sex == "M") 0 else 1
  }

  def parseIndividual(row: Array[String]): SynthIndividual = {
    SynthIndividual(
      Id.createPersonId(row(indIdIdx)),
      parseSex(row(indSexIdx)),
      row(indAgeIdx).toInt,
      if (row.length == 12) {
        row(indValTime).toDouble
      } else 18.0,
      row(indIncomeIdx).toDouble
    )
  }

  def parseHousehold(row: Array[String], hhIdStr: String): SynthHousehold = {
    SynthHousehold(
      Id.create(hhIdStr, classOf[Household]),
      row(carNumIdx).toInt,
      row(hhNumIdx).toInt,
      row(hhIncomeIdx).toDouble,
      row(hhTractIdx).toInt,
      geoConverter.transform(
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

trait GeoConverter {
  def transform(coord: Coord): Coord
}

case class WGSConverter(sourceCRS: String, targetCRS: String) extends GeoConverter {

  private val wgs2Utm: GeotoolsTransformation =
    new GeotoolsTransformation(sourceCRS, targetCRS)

  override def transform(coord: Coord): Coord = wgs2Utm.transform(coord)

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
    val envelopes = features.asScala
      .map(_.getDefaultGeometry)
      .collect { case g: Geometry =>
        wgsConverter.wgs2Utm(g.getEnvelope.getEnvelopeInternal)
      }
    val bounds = ShapeUtils.quadTreeBounds(envelopes)
    QuadTreeExtent(bounds.minx, bounds.miny, bounds.maxx, bounds.maxy)
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
    val _ = implicitly[HasXY[T]]

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

class SpatialSampler(sampleShape: String) {
  val shapeFileReader: ShapeFileReader = new ShapeFileReader
  shapeFileReader.readFileAndInitialize(sampleShape)
  val rng = new MersenneTwister(7571452) // Random.org
  val distribution: EnumeratedDistribution[SimpleFeature] = {
    val features = shapeFileReader.getFeatureCollection.features()
    val distributionList = mutable.Buffer[Pair[SimpleFeature, java.lang.Double]]()
    while (features.hasNext) {
      val feature = features.next()
      val popPct = feature.getAttribute("pop_pct").asInstanceOf[Double]
      distributionList += new Pair[SimpleFeature, java.lang.Double](feature, popPct)
    }
    //    if(distributionList.map(_.getValue).sum > 0) {}
    new EnumeratedDistribution[SimpleFeature](rng, JavaConverters.bufferAsJavaList(distributionList))
  }

  def getSample: SimpleFeature = distribution.sample()
}

object PlansSampler {

  import HasXY._

  val counter: Counter = new Counter("[" + this.getClass.getSimpleName + "] created household # ")

  private var planQt: Option[QuadTree[Plan]] = None
  var wgsConverter: Option[WGSConverter] = None
  val conf: Config = ConfigUtils.createConfig()

  private val sc: MutableScenario = ScenarioUtils.createMutableScenario(conf)
  private val newPop: Population = PopulationUtils.createPopulation(ConfigUtils.createConfig())
  val newPopAttributes: ObjectAttributes = newPop.getPersonAttributes
  val newVehicles: Vehicles = VehicleUtils.createVehiclesContainer()
  val newHHFac: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  val newHH: HouseholdsImpl = new HouseholdsImpl()
  val newHHAttributes: ObjectAttributes = newHH.getHouseholdAttributes
  val shapeFileReader: ShapeFileReader = new ShapeFileReader

  val modeAllocator: AvailableModeUtils.AllowAllModes.type = AvailableModeUtils.AllowAllModes

  private var synthHouseholds = Vector[SynthHousehold]()

  private var pop = Vector[Person]()
  var outDir: String = ""
  var sampleNumber: Int = 0
  var spatialSampler: SpatialSampler = _

  def init(args: Array[String]): Unit = {
    conf.plans.setInputFile(args(0))
    conf.network.setInputFile(args(2))

    sampleNumber = args(5).toInt
    sc.setLocked()
    ScenarioUtils.loadScenario(sc)
    shapeFileReader.readFileAndInitialize(args(1))

    spatialSampler = Try(new SpatialSampler(args(1))).getOrElse(null)
    val sourceCrs = MGC.getCRS(args(7))

    wgsConverter = Some(WGSConverter(args(7), args(8)))
    pop ++= scala.collection.JavaConverters
      .mapAsScalaMap(sc.getPopulation.getPersons)
      .values
      .toVector

    synthHouseholds ++=
      filterSynthHouseholds(
        synthHouseholdsToFilter = new SynthHouseholdParser(wgsConverter.get).parseFile(args(3)),
        aoiFeatures = shapeFileReader.getFeatureSet,
        sourceCRS = sourceCrs
      )

    planQt = Some(
      new QuadTreeBuilder(wgsConverter.get)
        .buildQuadTree(shapeFileReader.getFeatureSet, sourceCrs, pop)
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

  private def getClosestNPlans(spCoord: Coord, n: Int, withoutWork: Boolean = false): Set[Plan] = {
    val closestPlan = getClosestPlan(spCoord)
    var col = if (withoutWork && !hasNoWorkAct(closestPlan)) Set[Plan]() else Set(closestPlan)

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
        if (!col.contains(plan) && (!withoutWork || hasNoWorkAct(plan))) {
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
    synthHouseholdsToFilter: Vector[SynthHousehold],
    aoiFeatures: util.Collection[SimpleFeature],
    sourceCRS: CoordinateReferenceSystem
  ): Vector[SynthHousehold] = {

    if (spatialSampler == null) {
      val aoi: Geometry = new QuadTreeBuilder(wgsConverter.get)
        .geometryUnionFromShapefile(aoiFeatures, sourceCRS)
      synthHouseholdsToFilter
        .filter(hh => aoi.contains(MGC.coord2Point(hh.coord)))
        .take(sampleNumber)
    } else {
      val tract2HH = synthHouseholdsToFilter.groupBy(f => f.tract)
      val synthHHs = mutable.Buffer[SynthHousehold]()
      (0 to sampleNumber).foreach { _ =>
        {
          val sampleFeature = spatialSampler.getSample
          val sampleTract = sampleFeature.getAttribute("TRACTCE").asInstanceOf[String].toInt
          var hh = hhNewValue(tract2HH, sampleTract)
          while (synthHHs.exists(_.householdId.equals(hh.householdId))) {
            hh = hhNewValue(tract2HH, sampleTract)
          }
          if (hh.individuals.length != 0) {
            synthHHs += hh
          } else {
            println(s"empty household! ${hh.householdId}")
          }
        }
      }

      synthHHs.toVector
    }
  }

  @SuppressWarnings(Array("UnsafeTraversableMethods"))
  private def hhNewValue(
    tract2HH: Map[Int, Vector[SynthHousehold]],
    sampleTract: Int
  ): SynthHousehold = {
    Random.shuffle(tract2HH(sampleTract)).take(1).head
  }

  def addModeExclusions(person: Person): Unit = {
    val filteredPermissibleModes = modeAllocator
      .getPermissibleModes(person.getSelectedPlan)
      .asScala
      .filterNot(pm => PersonUtils.getAge(person) < 16 && pm.equalsIgnoreCase(CAR.toString))
    AvailableModeUtils.setAvailableModesForPerson(person, newPop, filteredPermissibleModes.toSeq)
  }

  def filterPopulationActivities() {
    val factory = newPop.getFactory
    newPop.getPersons.asScala.values.foreach { person =>
      @SuppressWarnings(Array("UnsafeTraversableMethods"))
      val origPlan = person.getPlans.asScala.head
      person.getPlans.clear()
      val newPlan = factory.createPlan()
      person.addPlan(newPlan)
      for { pe <- origPlan.getPlanElements.asScala if pe.isInstanceOf[Activity] } yield {
        val oldActivity = pe.asInstanceOf[Activity]
        if (oldActivity.getType.contains("Pt")) {
          //
        } else {
          val actCoord = new Coord(oldActivity.getCoord.getX, oldActivity.getCoord.getY)
          val activity = factory.createActivityFromCoord(oldActivity.getType, actCoord)
          activity.setEndTime(oldActivity.getEndTime)
          newPlan.addActivity(activity)
        }

      }

    }
  }

  def run(): Unit = {

    val carVehicleType = MatsimConversionTool.beamVehicleTypeToMatsimVehicleType(null)

    newVehicles.addVehicleType(carVehicleType)

    breakable {
      synthHouseholds.foreach(sh => {
        val numPersons = sh.individuals.length
        val N = if (numPersons > 0) {
          numPersons * 2
        } else {
          1
        }

        val closestPlans: Set[Plan] = getClosestNPlans(sh.coord, N)

        val selectedPlans = Random.shuffle(closestPlans).take(numPersons)
        val plansWithoutWork = getClosestNPlans(sh.coord, numPersons, withoutWork = true)

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

          val srcPlan = if (synthPerson.age > 18 || hasNoWorkAct(plan)) {
            plan
          } else {
            Random.shuffle(plansWithoutWork).headOption match {
              case Some(p) => p
              case None    => plan
            }
          }
          PopulationUtils.copyFromTo(srcPlan, newPlan)
          val homeActs = newPlan.getPlanElements.asScala
            .collect { case activity: Activity if activity.getType.equalsIgnoreCase("Home") => activity }

          homePlan match {
            case None =>
              homePlan = Some(newPlan)
              val homeCoord = homeActs.head.getCoord
              newHHAttributes.putAttribute(hhId.toString, HomeCoordX.entryName, homeCoord.getX)
              newHHAttributes.putAttribute(hhId.toString, HomeCoordY.entryName, homeCoord.getY)
              newHHAttributes.putAttribute(hhId.toString, HousingType.entryName, "House")
              snapPlanActivityLocsToNearestLink(newPlan)

            case Some(hp) =>
              val firstAct = PopulationUtils.getFirstActivity(hp)
              val firstActCoord = firstAct.getCoord
              for (act <- homeActs) {
                act.setCoord(firstActCoord)
              }
              snapPlanActivityLocsToNearestLink(newPlan)
          }

          PersonUtils.setAge(newPerson, synthPerson.age)
          val sex = if (synthPerson.sex == 0) {
            "M"
          } else {
            "F"
          }
          // TODO: Include non-binary gender if data available
          PersonUtils.setSex(newPerson, sex)
          newPopAttributes
            .putAttribute(newPerson.getId.toString, "valueOfTime", synthPerson.valueOfTime)
          newPopAttributes.putAttribute(newPerson.getId.toString, "income", synthPerson.income)
          addModeExclusions(newPerson)
        }

        if (newPop.getPersons.size() > sampleNumber)
          break()
      })
    }

    filterPopulationActivities()
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

  private def hasNoWorkAct(plan: Plan) = {
    !plan.getPlanElements.asScala.exists {
      case activity: Activity => activity.getType.equalsIgnoreCase("Work")
      case _                  => false
    }
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
  * $> gradle :execute -PmainClass=beam.utils.sampling.PlansSamplerApp
  * -PappArgs="['production/application-sfbay/population.xml.gz', 'production/application-sfbay/shape/bayarea_county_dissolve_4326.shp',
  * 'production/application-sfbay/physsim-network.xml', 'test/input/sf-light/ind_X_hh_out.csv.gz',
  * 'production/application-sfbay/vehicles.xml.gz', '413187', production/application-sfbay/samples', 'epsg:4326', 'epsg:26910']"
  *
  * for siouxfalls
  * test/input/siouxfalls/conversion-input/Siouxfalls_population.xml
  * test/input/siouxfalls/conversion-input/sioux_falls_population_counts_by_census_block_dissolved.shp
  * test/input/siouxfalls/conversion-input/Siouxfalls_network_PT.xml
  * test/input/siouxfalls/conversion-input/ind_X_hh_out.csv.gz
  * test/input/siouxfalls/conversion-input/transitVehicles.xml
  * 15000 test/input/siouxfalls/samples/15k epsg:4326 epsg:26914
  */
object PlansSamplerApp extends App {
  val sampler = PlansSampler
  sampler.init(args)
  sampler.run()
}
