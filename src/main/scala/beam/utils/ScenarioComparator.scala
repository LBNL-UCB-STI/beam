package beam.utils
import java.time.ZonedDateTime
import java.util.function.Predicate
import java.util.{Collections, Comparator}

import akka.actor.ActorRef
import beam.agentsim.agents.choice.mode.ModeSubsidy
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, FuelType}
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.AttributesOfIndividual
import beam.utils.csv.readers.PlanReaderCsv
import com.google.inject.Injector
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.{Person, Plan, PlanElement}
import org.matsim.contrib.socnetsim.utils.IdentifiableCollectionsUtils
import org.matsim.core.controler.ControlerI
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle
import spark.utils.CollectionUtils

import scala.collection.concurrent.TrieMap
import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.util.control.Breaks

object ScenarioComparator extends App with Comparator[MutableScenario]{

  val testOutputDir = "output/test/"
  val configFile = "test/input/beamville/beam.conf"
  val xmlScenario = loadScenarioUsingXmlMode()
  val csvScenario = loadScenarioUsingCsvMode()
  logScenario(xmlScenario)
  logScenario(csvScenario)

  val scenarioEquality = compare(xmlScenario, csvScenario)
  println("Scenarios are equal " + scenarioEquality)

  def compare(o1: MutableScenario, o2: MutableScenario): Int = {

    var flag = 0
    var houseHoldsEqual = 0
    var vehiclesEqual = 0
    var personsEqual = 0
    if(flag == 0 && HouseHoldComparator.compareHouseHolds(o1, o2) != 0)
      houseHoldsEqual = 1

    // if(flag == 0 && VehicleComparator.compareVehicles(o1, o2) != 0)
    //  vehiclesEqual = 1

    if(flag == 0 && PersonComparator.comparePersons(o1, o2) != 0)
      personsEqual = 1

    println("HouseHolds are equal " + houseHoldsEqual)
    println("Vehicles are equal " + vehiclesEqual)
    println("Persons are equal " + personsEqual)

    if(houseHoldsEqual == 0 && personsEqual == 0 && vehiclesEqual == 0) flag = 0
    else flag = 1

    flag
  }


  def loadScenarioUsingXmlMode(): MutableScenario = {

    val config = BeamConfigUtils
      .parseFileSubstitutingInputDirectory(configFile)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .resolve()

    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()

    //matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    //  ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)
    //  LoggingUtil.createFileLogger(outputDirectory)
    //  matsimConfig.controler.setOutputDirectory(outputDirectory)
    //  matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]

    val beamServices = getBeamServices(config)

    scenario
  }

  def loadScenarioUsingCsvMode() : MutableScenario = {

    val config = BeamConfigUtils
      .parseFileSubstitutingInputDirectory(configFile)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .resolve()
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSamConf()
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]

    val beamServices = getBeamServices(config)

    val planReaderCsv: PlanReaderCsv = new PlanReaderCsv(scenario, beamServices)

    for(h: Household <- planReaderCsv.getHouseHoldsList.asScala){
      scenario.getHouseholds.getHouseholds.put(h.getId, h)
    }

    scenario
  }

  def getBeamServices(config: com.typesafe.config.Config):BeamServices = {
    val beamServices: BeamServices = new BeamServices {
      override lazy val controler: ControlerI = ???
      override val beamConfig: BeamConfig = BeamConfig(config)
      override lazy val geo: beam.sim.common.GeoUtils = new GeoUtilsImpl(this)
      override var modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator = _
      override val dates: DateUtils = DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
      override var beamRouter: ActorRef = _
      override val personRefs: TrieMap[Id[Person], ActorRef] = TrieMap()
      override val vehicles: TrieMap[Id[BeamVehicle], BeamVehicle] = TrieMap()
      override var personHouseholds: Map[Id[Person], Household] = Map()
      val fuelTypes: TrieMap[Id[FuelType], FuelType] =
        BeamServices.readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamFuelTypesFile)
      val vehicleTypes: TrieMap[Id[BeamVehicleType], BeamVehicleType] =
        BeamServices.readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.beamVehicleTypesFile, fuelTypes)
      val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle] =
        BeamServices.readVehiclesFile(beamConfig.beam.agentsim.agents.vehicles.beamVehiclesFile, vehicleTypes)
      override val modeSubsidies: ModeSubsidy =
        ModeSubsidy(ModeSubsidy.loadSubsidies(beamConfig.beam.agentsim.agents.modeSubsidy.file))

      override def startNewIteration(): Unit = throw new Exception("???")

      override protected def injector: Injector = throw new Exception("???")

      override def matsimServices_=(x$1: org.matsim.core.controler.MatsimServices): Unit = ???

      override def rideHailIterationHistoryActor_=(x$1: akka.actor.ActorRef): Unit = ???

      override val tazTreeMap: beam.agentsim.infrastructure.TAZTreeMap =
        beam.sim.BeamServices.getTazTreeMap(beamConfig.beam.agentsim.taz.file)

      override def matsimServices: org.matsim.core.controler.MatsimServices = ???

      override def rideHailIterationHistoryActor: akka.actor.ActorRef = ???
    }

    beamServices
  }


  def logScenario(scenario: MutableScenario) = {
    println("Scenario is loaded " + scenario.toString)

    println("HouseHolds")
    scenario.getHouseholds.getHouseholds.forEach{
      case (hId: Id[Household], h: Household) => {
        println("hId => " + hId + ", h => " + h.getMemberIds.toString + ", " + h.getVehicleIds.toString + ", " + h.getIncome.toString)
      }
    }
    println("--")

    println("Vehicles")
    scenario.getVehicles.getVehicles.forEach{
      case (vId: Id[Vehicle], v: Vehicle) => {
        println("vId => " + vId + ", v => " + v.toString)
      }
    }
    println("--")

    println("Persons")
    scenario.getPopulation.getPersons.forEach{
      case(pId: Id[Person], p: Person) => {
        println("pId => " + pId + ", p => " + p.toString)
      }
    }
    println("--")
  }

}



object HouseHoldComparator extends Comparator[Household]{

  override def compare(o1: Household, o2: Household): Int = {

    var flag = 0
    if(flag == 0 && o2 == null) flag = 1

    if(flag == 0) {

      Collections.sort(o1.getMemberIds)
      Collections.sort(o2.getMemberIds)
      Collections.sort(o1.getVehicleIds)
      Collections.sort(o2.getVehicleIds)

      if (!(o1.getId == o2.getId &&
          o1.getIncome.getCurrency == o2.getIncome.getCurrency &&
          o1.getIncome.getIncome == o2.getIncome.getIncome &&
          o1.getIncome.getIncomePeriod.equals(o2.getIncome.getIncomePeriod) &&
          //o1.getIncome.getIncomePeriod.values().deep == o2.getIncome.getIncomePeriod.values.deep &&
          /*Vehicle ids dont match because we are generating vehicles on the fly dynamically in the case of csv data*/
//          o1.getVehicleIds.equals(o2.getVehicleIds) &&
          o1.getMemberIds.equals(o2.getMemberIds)))
        flag = 1
    }

    flag
  }

  def compareHouseHolds(o1: MutableScenario, o2: MutableScenario) : Int = {
    var flag = 0

    val houseHolds1 = o1.getHouseholds.getHouseholds
    val houseHolds2 = o2.getHouseholds.getHouseholds

    if(flag == 0 && houseHolds1.size() != houseHolds2.size()) flag = 1

    if(flag == 0) {

      Breaks.breakable{
        houseHolds1.keySet().forEach {
          case (hhId1: Id[Household]) => {

            val hh1 = houseHolds1.get(hhId1)
            val hh2 = houseHolds2.get(hhId1)

            HouseHoldComparator.compare(hh1, hh2) match {
              case 1 => {
                flag = 1
                Breaks.break
              }
              case _ =>
            }
          }
        }
      }
    }

    flag
  }

}

object VehicleComparator extends Comparator[Vehicle]{

  override def compare(v1: Vehicle, v2: Vehicle): Int = {
    if(
      v1.getId == v2.getId &&
      v1.getType.getCapacity == v2.getType.getCapacity &&
      v1.getType.getDescription == v2.getType.getDescription &&
      v1.getType.getDoorOperationMode == v2.getType.getDoorOperationMode &&
      v1.getType.getFlowEfficiencyFactor == v2.getType.getFlowEfficiencyFactor &&
      v1.getType.getLength == v2.getType.getLength &&
      v1.getType.getMaximumVelocity == v2.getType.getMaximumVelocity &&
      v1.getType.getPcuEquivalents == v2.getType.getPcuEquivalents &&
      v1.getType.getWidth == v2.getType.getWidth
    ) 0
    else 1
  }

  def compareVehicles(o1: MutableScenario, o2: MutableScenario) : Int = {
    var flag = 0

    val vehicles1 = o1.getVehicles.getVehicles
    val vehicles2 = o2.getVehicles.getVehicles

    if(flag == 0 && vehicles1.size() != vehicles2.size()) flag = 1

    if(flag == 0) {

      Breaks.breakable{
        vehicles1.keySet().forEach {
          case (vId1: Id[Vehicle]) => {

            val v1 = vehicles1.get(vId1)
            val v2 = vehicles2.get(vId1)

            VehicleComparator.compare(v1, v2) match {
              case 1 => {
                flag = 1
                Breaks.break
              }
              case _ =>
            }
          }
        }
      }
    }

    flag
  }

}

object PersonComparator extends Comparator[Person]{

  def compareSelectedPlans(p1: Plan, p2: Plan): Boolean = {

    var flag = 0
    if((p1 != null && p2 == null) || (p1 == null && p2 != null)) flag = 1

    if(flag == 0 && p1.getPlanElements.size() != p2.getPlanElements.size()) flag = 1


    if(flag == 0) {
      val pElements1 = p1.getPlanElements
      val pElements2 = p2.getPlanElements

      for (i <- 0 until pElements1.size()) {
        val pe1 = pElements1.get(i)
        val pe2 = pElements2.get(i)
        if (pe1.getAttributes.toString.equals(pe2.getAttributes.toString) == false) {
          flag = 1
        }
      }
    }


    flag match {
      case 0 => true
      case _ => false
    }
  }

  override def compare(p1: Person, p2: Person): Int = {
    if(p1.getId == p2.getId &&

      //p1.getAttributes.toString.equals(p2.getAttributes.toString) &&
      ///*p1.getCustomAttributes.equals(p2.getCustomAttributes) &&*/
      compareSelectedPlans(p1.getSelectedPlan, p2.getSelectedPlan)
    ) 0
    else 1
  }

  def comparePersons(o1: MutableScenario, o2: MutableScenario) : Int = {
    var flag = 0

    val persons1 = o1.getPopulation.getPersons
    val persons2 = o2.getPopulation.getPersons

    if(flag == 0 && persons1.size() != persons2.size()) flag = 1

    if(flag == 0) {

      Breaks.breakable{
        persons1.keySet().forEach {
          case (pId1: Id[Person]) => {

            val p1 = persons1.get(pId1)
            val p2 = persons2.get(pId1)

            PersonComparator.compare(p1, p2) match {
              case 1 => {
                flag = 1
                //Breaks.break
              }
              case _ =>
            }
          }
        }
      }
    }

    flag
  }

}