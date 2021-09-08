package beam.utils.plan

import java.io.{BufferedWriter, File, FileWriter}
import java.nio.file.{Files, Paths}

import beam.utils.plan.sampling.AvailableModeUtils.AllowAllModes
import beam.utils.plan.sampling.HouseholdAttrib.{HomeCoordX, HomeCoordY, HousingType}
import beam.utils.plan.sampling.PlansSampler.newPop
import beam.utils.plan.sampling.PopulationAttrib.Rank
import beam.utils.plan.sampling._
import beam.utils.scripts.PopulationWriterCSV
import org.matsim.api.core.v01.network.Node
import org.matsim.api.core.v01.population.{Activity, Person, Plan, Population}
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.population.{PersonUtils, PopulationUtils}
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.geometry.transformations.GeotoolsTransformation
import org.matsim.core.utils.misc.Counter
import org.matsim.households.Income.IncomePeriod.year
import org.matsim.households._
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.matsim.vehicles.{Vehicle, VehicleUtils, VehicleWriterV1, Vehicles}

import scala.collection.JavaConverters._
import scala.collection.{immutable, JavaConverters}
import scala.util.Random

object PlansBuilder {
  val counter: Counter = new Counter("[" + this.getClass.getSimpleName + "] created household # ")

  var utmConverter: UTMConverter = _
  val conf: Config = ConfigUtils.createConfig()

  private val sc: MutableScenario = ScenarioUtils.createMutableScenario(conf)

  private val newPop: Population =
    PopulationUtils.createPopulation(ConfigUtils.createConfig())
  val newPopAttributes: ObjectAttributes = newPop.getPersonAttributes
  val newVehicles: Vehicles = VehicleUtils.createVehiclesContainer()
  val newHHFac: HouseholdsFactoryImpl = new HouseholdsFactoryImpl()
  val newHH: HouseholdsImpl = new HouseholdsImpl()
  val newHHAttributes: ObjectAttributes = newHH.getHouseholdAttributes

  val modeAllocator: AllowAllModes.type = AllowAllModes

  private var synthHouseholds = Vector[SynthHousehold]()

  private var pop = Vector[Person]()
  var outDir: String = ""
  var sampleNumber: Int = 0
  val rand = new Random(4175L) // should this Random use a fixed seed from beamConfig ?

  case class UTMConverter(sourceCRS: String, targetCRS: String) extends GeoConverter {

    private lazy val utm2Wgs: GeotoolsTransformation =
      new GeotoolsTransformation(sourceCRS, targetCRS)

    override def transform(coord: Coord): Coord = {
      if (coord.getX > 1.0 | coord.getX < -0.0) {
        utm2Wgs.transform(coord)
      } else {
        coord
      }
    }
  }

  def init(args: Array[String]): Unit = {
    conf.plans.setInputFile(args(0))
    conf.network.setInputFile(args(1))
    conf.vehicles.setVehiclesFile(args(2))
    sampleNumber = args(3).toInt
    sc.setLocked()
    ScenarioUtils.loadScenario(sc)
    outDir = args(4)

    val srcCSR = if (args.length > 5) args(5) else "epsg:4326"
    val tgtCSR = if (args.length > 6) args(6) else "epsg:26910"
    utmConverter = UTMConverter(srcCSR, tgtCSR)
    pop ++= scala.collection.JavaConverters
      .mapAsScalaMap(sc.getPopulation.getPersons)
      .values
      .toVector

    val households = new SynthHouseholdParser(utmConverter) {
      override def parseFile(synthFileName: String): Vector[SynthHousehold] = {
        val resHHMap = scala.collection.mutable.Map[String, SynthHousehold]()

        val nodes = sc.getNetwork.getNodes.values().toArray(new Array[Node](0))

        for (indId <- 0 to sampleNumber) {
          val node = nodes(rand.nextInt(nodes.length - 1)).getCoord
          val row = Array(
            indId.toString, //indId
            rand.nextInt(sampleNumber).toString, //hhId
            rand.nextInt(4).toString, //hhNum
            rand.nextInt(3).toString, //carNum
            rand.nextInt(1000000).toString, //income
            0.toString, //
            rand.nextInt(1).toString, //sex
            rand.nextInt(100).toString, //age
            0.toString, //hhTract
            node.getX.toString, //coord.x
            node.getY.toString, //coord.y
            (rand.nextDouble() * 23).toString
          ) //time

          val hhIdStr = row(1)
          resHHMap.get(hhIdStr) match {
            case Some(hh: SynthHousehold) => hh.addIndividual(parseIndividual(row))
            case None                     => resHHMap += (hhIdStr -> parseHousehold(row, hhIdStr))
          }
        }

        resHHMap.values.toVector
      }
    }.parseFile(null)
    synthHouseholds ++= households
  }

  def addModeExclusions(person: Person): Unit = {
    val permissibleModes = modeAllocator
      .getPermissibleModes(person.getSelectedPlan)
      .asScala
    AvailableModeUtils.setAvailableModesForPerson(person, newPop, permissibleModes.toSeq)
  }

  def run(): Unit = {

    Files.createDirectories(Paths.get(outDir))
    val csvFileName = s"$outDir/vehicles.csv"
    val out = new BufferedWriter(new FileWriter(new File(csvFileName)))
    out.write("vehicleId,vehicleTypeId")
    out.newLine()

    @SuppressWarnings(Array("UnsafeTraversableMethods"))
    val carVehicleType = JavaConverters
      .collectionAsScalaIterable(sc.getVehicles.getVehicleTypes.values())
      .head
    carVehicleType.setFlowEfficiencyFactor(1069)
    carVehicleType.getEngineInformation.setGasConsumption(1069)
    newVehicles.addVehicleType(carVehicleType)

    synthHouseholds.foreach(sh => {
      val numPersons = sh.individuals.length

      val hhId = sh.householdId
      val spHH = newHHFac.createHousehold(hhId)

      // Add household to households and increment counter now
      newHH.getHouseholds.put(hhId, spHH)

      // Set hh income
      spHH.setIncome(newHHFac.createIncome(sh.hhIncome.toInt, year))

      counter.incCounter()
      // Create and add car identifiers
      (0 to sh.vehicles).foreach(x => {
        val vId = s"${counter.getCounter}-$x"
        val vehicleId = Id.createVehicleId(vId)
        out.write(s"$vId,Car")
        out.newLine()
        val vehicle: Vehicle =
          VehicleUtils.getFactory.createVehicle(vehicleId, carVehicleType)
        newVehicles.addVehicle(vehicle)
        spHH.getVehicleIds.add(vehicleId)
      })

      var homePlan: Option[Plan] = None

      var ranks: immutable.Seq[Int] = 0 to sh.individuals.length
      ranks = Random.shuffle(ranks)

      for (idx <- 0 until numPersons) {
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
        PopulationUtils.copyFromTo(pop(idx % pop.size).getPlans.get(0), newPlan)
        val homeActs = newPlan.getPlanElements.asScala
          .collect { case activity: Activity if activity.getType.equalsIgnoreCase("Home") => activity }

        homePlan match {
          case None =>
            homePlan = Some(newPlan)
            val homeCoord = homeActs.head.getCoord
            newHHAttributes.putAttribute(hhId.toString, HomeCoordX.entryName, homeCoord.getX)
            newHHAttributes.putAttribute(hhId.toString, HomeCoordY.entryName, homeCoord.getY)
            newHHAttributes.putAttribute(hhId.toString, HousingType.entryName, "House")

          case Some(hp) =>
            val firstAct = PopulationUtils.getFirstActivity(hp)
            val firstActCoord = firstAct.getCoord
            for (act <- homeActs) {
              act.setCoord(firstActCoord)
            }
        }

        PersonUtils.setAge(newPerson, synthPerson.age)
        val sex = if (synthPerson.sex == 0) { "M" }
        else { "F" }
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
    out.close()
  }

  /**
    * This script is designed to create input data for BEAM. It expects the following inputs [provided in order of
    * command-line args]:
    *
    * [0] Raw plans input filename
    * [1] Network input filename
    * [2] Default vehicle type(s) input filename
    * [3] Number of persons to sample (e.g., 1k, 5k, etc.)
    * [4] Output directory
    * [5,6] Target CRS (optional) defaults 'epsg:4326', 'epsg:26910'
    *
    * Run from directly from CLI with, for example:
    *
    * $> gradle :execute -PmainClass=beam.utils.plan.PlansBuilder
    *   -PappArgs="['test/input/beamville/population.xml',
    *   'test/input/beamville/physsim-network.xml',
    *   'test/input/beamville/vehicles.xml', '2000',
    *   'test/input/beamville/samples', 'epsg:4326', 'epsg:26910']"
    */
  def main(args: Array[String]): Unit = {
    val builder = PlansBuilder
    builder.init(args)
    builder.run()
  }
}
