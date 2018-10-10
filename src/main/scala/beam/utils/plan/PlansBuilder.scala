package beam.utils.plan

import beam.utils.gis.Plans2Shapefile
import beam.utils.plan.sampling.HouseholdAttrib.{HomeCoordX, HomeCoordY, HousingType}
import beam.utils.plan.sampling.PopulationAttrib.Rank
import beam.utils.plan.sampling._
import beam.utils.scripts.PopulationWriterCSV
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Node
import org.matsim.api.core.v01.population.{Person, Plan, Population}
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.network.NetworkUtils
import org.matsim.core.population.io.PopulationWriter
import org.matsim.core.population.{PersonUtils, PopulationUtils}
import org.matsim.core.router.StageActivityTypesImpl
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.misc.Counter
import org.matsim.households.Income.IncomePeriod.year
import org.matsim.households._
import org.matsim.utils.objectattributes.{ObjectAttributes, ObjectAttributesXmlWriter}
import org.matsim.vehicles.{Vehicle, VehicleUtils, VehicleWriterV1, Vehicles}

import scala.collection.{JavaConverters, immutable}
import scala.util.Random

object PlansBuilder {
  val availableModeString: String = "available-modes"
  val counter: Counter = new Counter("[" + this.getClass.getSimpleName + "] created household # ")

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

  val modeAllocator: AvailableModeUtils.AllowAllModes =
    new AvailableModeUtils.AllowAllModes

  private var synthHouseholds = Vector[SynthHousehold]()

  private var pop = Vector[Person]()
  var outDir: String = ""
  var sampleNumber: Int = 0
  val rand = new Random(4175l)

  def init(args: Array[String]): Unit = {
    conf.plans.setInputFile(args(0))
    conf.network.setInputFile(args(1))
    conf.vehicles.setVehiclesFile(args(2))
    sampleNumber = args(3).toInt
    sc.setLocked()
    ScenarioUtils.loadScenario(sc)
    outDir = args(4)

    val srcCSR = if(args.length > 5) args(5) else "epsg:4326"
    val tgtCSR = if(args.length > 6) args(6) else "epsg:26910"
    wgsConverter = Some(WGSConverter(srcCSR, tgtCSR))
    pop ++= scala.collection.JavaConverters
      .mapAsScalaMap(sc.getPopulation.getPersons)
      .values
      .toVector

    val households = new SynthHouseholdParser(wgsConverter.get){
      override def parseFile(synthFileName: String): Vector[SynthHousehold] = {
        val resHHMap = scala.collection.mutable.Map[String, SynthHousehold]()

        val nodes = sc.getNetwork.getNodes.values().toArray(new Array[Node](0))

        for (indId <- 0 to sampleNumber) {
          val node: Node = nodes(rand.nextInt(nodes.length-1))
          val row = Array(indId.toString, //indId
            rand.nextInt(sampleNumber).toString, //hhId
            rand.nextInt(4).toString, //hhNum
            rand.nextInt(3).toString, //carNum
            rand.nextInt(Int.MaxValue).toString, //income
            0.toString, //
            rand.nextInt(1).toString, //sex
            rand.nextInt(100).toString, //age
            0.toString, //hhTract
            node.getCoord.getX.toString, //coord.x
            node.getCoord.getY.toString, //coord.y
            (rand.nextDouble() * 23).toString) //time

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

  private def snapPlanActivityLocsToNearestLink(plan: Plan): Plan = {

    val allActivities =
      PopulationUtils.getActivities(plan, new StageActivityTypesImpl(""))

    allActivities.forEach(x => {
      val nearestLink = NetworkUtils.getNearestLink(sc.getNetwork, x.getCoord)
      x.setCoord(nearestLink.getCoord)
    })
    plan
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

      for(idx <- 0 until numPersons)  {
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
