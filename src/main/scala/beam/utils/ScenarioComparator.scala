package beam.utils

import java.nio.file.Paths
import java.time.ZonedDateTime
import java.util.{Collections, Comparator}

import akka.actor.ActorRef
import beam.agentsim.agents.choice.mode.{ModeIncentive, PtFares}
import beam.agentsim.agents.modalbehaviors.ModeChoiceCalculator
import beam.agentsim.agents.vehicles.FuelType.FuelType
import beam.agentsim.agents.vehicles._
import beam.router.Modes
import beam.router.r5.DefaultNetworkCoordinator
import beam.sim.BeamServices
import beam.sim.common.GeoUtilsImpl
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.AttributesOfIndividual
import beam.utils.BeamVehicleUtils.{readBeamVehicleTypeFile, readFuelTypeFile, readVehiclesFile}
import beam.utils.plan.sampling.AvailableModeUtils
import com.google.inject.Injector
import com.typesafe.config.ConfigValueFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Person, Plan}
import org.matsim.core.controler.ControlerI
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.households.Household
import org.matsim.vehicles.Vehicle

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.control.Breaks

object ScenarioComparator extends App with Comparator[MutableScenario] {

  val testOutputDir = "output/test/"
  val configFile = "test/input/beamville/beam.conf"
  val xmlScenario = loadScenarioUsingXmlMode()
  val csvScenario = loadScenarioUsingCsvMode()

  var b1: BeamServices = _
  var b2: BeamServices = _

  logScenario(xmlScenario)
  logScenario(csvScenario)

  val scenarioEquality = compare(xmlScenario, csvScenario)
  println("Scenarios are equal " + scenarioEquality)

  def compare(o1: MutableScenario, o2: MutableScenario): Int = {

    var flag = 0
    var houseHoldsEqual = 0
    var personsEqual = 0
    if (flag == 0 && HouseHoldComparator.compareHouseHolds(o1, o2) != 0)
      houseHoldsEqual = 1

    if (flag == 0 && PersonComparator.comparePersons(o1, o2) != 0)
      personsEqual = 1

    println("HouseHolds are equal " + houseHoldsEqual)
    println("Persons are equal " + personsEqual)

    if (houseHoldsEqual == 0 && personsEqual == 0) flag = 0
    else flag = 1

    flag
  }

  def loadScenarioUsingXmlMode(): MutableScenario = {

    val config = BeamConfigUtils
      .parseFileSubstitutingInputDirectory(configFile)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .resolve()

    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()

    //matsimConfig.planCalcScore().setMemorizingExperiencedPlans(true)
    //  ReflectionUtils.setFinalField(classOf[StreetLayer], "LINK_RADIUS_METERS", 2000.0)
    //  LoggingUtil.createFileLogger(outputDirectory)
    //  matsimConfig.controler.setOutputDirectory(outputDirectory)
    //  matsimConfig.controler().setWritePlansInterval(beamConfig.beam.outputs.writePlansInterval)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]

    val beamServices = getBeamServices(config)
    b1 = beamServices

    scenario
  }

  def loadScenarioUsingCsvMode(): MutableScenario = {

    val config = BeamConfigUtils
      .parseFileSubstitutingInputDirectory(configFile)
      .withValue("beam.outputs.baseOutputDirectory", ConfigValueFactory.fromAnyRef(testOutputDir))
      .resolve()
    val matsimConfig = new MatSimBeamConfigBuilder(config).buildMatSimConf()
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]

    val beamServices = getBeamServices(config)

    b2 = beamServices

    scenario
  }

  def getBeamServices(config: com.typesafe.config.Config): BeamServices = {
    val beamServices: BeamServices = new BeamServices {
      override lazy val injector: Injector = ???
      override lazy val controler: ControlerI = ???
      override val beamConfig: BeamConfig = BeamConfig(config)
      override lazy val geo: beam.sim.common.GeoUtils = new GeoUtilsImpl(beamConfig)
      val transportNetwork = DefaultNetworkCoordinator(beamConfig).transportNetwork
      override var modeChoiceCalculatorFactory: AttributesOfIndividual => ModeChoiceCalculator = _
      override val dates: DateUtils = DateUtils(
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate).toLocalDateTime,
        ZonedDateTime.parse(beamConfig.beam.routing.baseDate)
      )
      override var beamRouter: ActorRef = _
      override var personHouseholds: Map[Id[Person], Household] = Map()

      // TODO Fix me once `TrieMap` is removed
      val fuelTypePrices: Map[FuelType, Double] =
        readFuelTypeFile(beamConfig.beam.agentsim.agents.vehicles.fuelTypesFilePath).toMap

      val vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType] =
        readBeamVehicleTypeFile(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath, fuelTypePrices)

      private val baseFilePath = Paths.get(beamConfig.beam.agentsim.agents.vehicles.vehicleTypesFilePath).getParent
      private val vehicleCsvReader = new VehicleCsvReader(beamConfig)
      private val consumptionRateFilterStore =
        new ConsumptionRateFilterStoreImpl(
          vehicleCsvReader.getVehicleEnergyRecordsUsing,
          Option(baseFilePath.toString),
          primaryConsumptionRateFilePathsByVehicleType =
            vehicleTypes.values.map(x => (x, x.primaryVehicleEnergyFile)).toIndexedSeq,
          secondaryConsumptionRateFilePathsByVehicleType =
            vehicleTypes.values.map(x => (x, x.secondaryVehicleEnergyFile)).toIndexedSeq
        )
      val vehicleEnergy = new VehicleEnergy(
        consumptionRateFilterStore,
        vehicleCsvReader.getLinkToGradeRecordsUsing
      )

      // TODO Fix me once `TrieMap` is removed
      val privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle] =
        TrieMap(
          readVehiclesFile(beamConfig.beam.agentsim.agents.vehicles.vehiclesFilePath, vehicleTypes).toSeq: _*
        )

      override def startNewIteration(): Unit = throw new Exception("???")

      override def matsimServices_=(x$1: org.matsim.core.controler.MatsimServices): Unit = ???

      override val tazTreeMap: beam.agentsim.infrastructure.TAZTreeMap =
        beam.sim.BeamServices.getTazTreeMap(beamConfig.beam.agentsim.taz.filePath)
      override val modeIncentives: ModeIncentive = ???

      override def matsimServices: org.matsim.core.controler.MatsimServices = ???

      override lazy val rideHailTransitModes: Seq[Modes.BeamMode] = ???
      override lazy val agencyAndRouteByVehicleIds: TrieMap[Id[Vehicle], (String, String)] = ???
      override lazy val ptFares: PtFares = ???
      override def networkHelper: NetworkHelper = ???
      override def setTransitFleetSizes(
        tripFleetSizeMap: mutable.HashMap[String, Integer]
      ): Unit = {}
    }

    beamServices
  }

  def logScenario(scenario: MutableScenario): Unit = {
    println("Scenario is loaded " + scenario.toString)

    println("HouseHolds")
    scenario.getHouseholds.getHouseholds.forEach {
      case (hId: Id[Household], h: Household) => {
        println(
          "hId => " + hId + ", h => " + h.getMemberIds.toString + ", " + h.getVehicleIds.toString + ", " + h.getIncome.toString
        )
      }
    }
    println("--")

    println("Vehicles")
    scenario.getVehicles.getVehicles.forEach {
      case (vId: Id[Vehicle], v: Vehicle) => {
        println("vId => " + vId + ", v => " + v.toString)
      }
    }

    println("--")

    println("Persons")
    scenario.getPopulation.getPersons.forEach {
      case (pId: Id[Person], p: Person) => {
        println("pId => " + pId + ", p => " + p.toString)
      }
    }
    println("--")
  }

}

object HouseHoldComparator extends Comparator[Household] {

  override def compare(o1: Household, o2: Household): Int = {

    var flag = 0
    if (flag == 0 && o2 == null) flag = 1

    if (flag == 0) {

      Collections.sort(o1.getMemberIds)
      Collections.sort(o2.getMemberIds)
      Collections.sort(o1.getVehicleIds)
      Collections.sort(o2.getVehicleIds)

      if (!(o1.getId == o2.getId &&
          o1.getIncome.getCurrency == o2.getIncome.getCurrency &&
          o1.getIncome.getIncome == o2.getIncome.getIncome &&
          o1.getIncome.getIncomePeriod.equals(o2.getIncome.getIncomePeriod) &&
          o1.getAttributes.toString.equals(o2.getAttributes.toString) &&
          //o1.getIncome.getIncomePeriod.values().deep == o2.getIncome.getIncomePeriod.values.deep &&
          /*Vehicle ids dont match because we are generating vehicles on the fly dynamically in the case of csv data*/
//          o1.getVehicleIds.equals(o2.getVehicleIds) &&
          o1.getMemberIds.equals(o2.getMemberIds)))
        flag = 1
    }

    flag
  }

  def compareHouseHolds(o1: MutableScenario, o2: MutableScenario): Int = {
    var flag = 0

    val houseHolds1 = o1.getHouseholds.getHouseholds
    val houseHolds2 = o2.getHouseholds.getHouseholds

    if (flag == 0 && houseHolds1.size() != houseHolds2.size()) flag = 1

    Breaks.breakable {
      o1.getHouseholds.getHouseholds.forEach {
        case (hhId: Id[Household], hh: Household) => {
          //o1.getHouseholds.getHouseholdAttributes.removeAttribute(hhId.toString, "housingtype")
          val x1 = o1.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString, "homecoordx")
          val y1 = o1.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString, "homecoordy")
          val x2 = o2.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString, "homecoordx")
          val y2 = o2.getHouseholds.getHouseholdAttributes.getAttribute(hhId.toString, "homecoordy")

          if (x1 != x2 || y1 != y2) {
            flag = 1
            Breaks.break
          }
        }
      }
    }

    /*if(!o1.getHouseholds.getHouseholdAttributes.toString.equals(o2.getHouseholds.getHouseholdAttributes.toString))
      flag = 1*/

    if (flag == 0) {

      Breaks.breakable {
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

object VehicleComparator extends Comparator[BeamVehicle] {

  override def compare(v1: BeamVehicle, v2: BeamVehicle): Int = {
    if (v1.id == v2.id
        && v1.beamVehicleType.equals(v2.beamVehicleType)) 0
    else 1
  }

}

object PersonComparator extends Comparator[Person] {

  def compareSelectedPlans(p1: Plan, p2: Plan): Boolean = {

    var flag = 0
    if ((p1 != null && p2 == null) || (p1 == null && p2 != null)) flag = 1

    if (flag == 0 && p1.getPlanElements.size() != p2.getPlanElements.size()) flag = 1

    if (flag == 0) {
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
    if (p1.getId == p2.getId &&
        p1.getPlans.size() == p2.getPlans.size() &&
        compareSelectedPlans(p1.getSelectedPlan, p2.getSelectedPlan)) 0
    else 1
  }

  def comparePersons(o1: MutableScenario, o2: MutableScenario): Int = {
    var flag = 0

    /*if(!o1.getPopulation.getPersonAttributes.toString.equals(o2.getPopulation.getPersonAttributes.toString))
      flag = 1*/

    Breaks.breakable {
      o1.getPopulation.getPersons.forEach {
        case (pId: Id[Person], p: Person) => {
          val age1 = o1.getPopulation.getPersonAttributes.getAttribute(pId.toString, "age")
          val age2 = o2.getPopulation.getPersonAttributes.getAttribute(pId.toString, "age")

          val availableModes1 = AvailableModeUtils.availableModesForPerson(p)
          val availableModes2 = AvailableModeUtils.availableModesForPerson(o2.getPopulation.getPersons.get(pId))
          /*age1 != age2 ||*/
          if (availableModes1 != availableModes2) {
            flag = 1
            Breaks.break()
          }
        }
      }
    }

    val persons1 = o1.getPopulation.getPersons
    val persons2 = o2.getPopulation.getPersons

    if (flag == 0 && persons1.size() != persons2.size()) flag = 1

    if (flag == 0) {

      Breaks.breakable {
        persons1.keySet().forEach {
          case (pId1: Id[Person]) => {

            val p1 = persons1.get(pId1)
            val p2 = persons2.get(pId1)

            PersonComparator.compare(p1, p2) match {
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
