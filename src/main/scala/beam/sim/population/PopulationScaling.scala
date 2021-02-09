package beam.sim.population

import java.io.{Closeable, File, FileWriter}

import beam.sim.config.BeamConfig
import beam.sim.{BeamScenario, BeamServices}
import beam.utils.csv.GenericCsvReader
import beam.sim.config.BeamConfig.Beam.Exchange.Scenario
import beam.sim.metrics.BeamStaticMetricsWriter
import com.typesafe.scalalogging.LazyLogging
import org.apache.commons.math3.distribution.EnumeratedDistribution
import org.apache.commons.math3.random.MersenneTwister
import org.apache.commons.math3.util.{Pair => PPair}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.{Activity, Person, PlanElement}
import org.matsim.core.population.{PersonUtils, PopulationUtils}
import org.matsim.core.scenario.MutableScenario
import org.matsim.households.{Household, HouseholdImpl}
import org.matsim.vehicles.Vehicle

import scala.collection.{mutable, JavaConverters}
import scala.util.Random
import scala.collection.JavaConverters._
import beam.utils.CloseableUtil.RichCloseable
import beam.utils.MathUtils

import scala.collection.mutable.Stack.StackBuilder
import scala.reflect.ClassTag

class PopulationScaling extends LazyLogging {

  def upSample(beamServices: BeamServices, scenario: MutableScenario, beamScenario: BeamScenario): Unit = {
    val beamConfig = beamServices.beamConfig
    logger.info(s"""Before sampling:
                   |Number of households: ${scenario.getHouseholds.getHouseholds.keySet.size}
                   |Number of vehicles: ${getVehicleGroupingStringUsing(
                     scenario.getVehicles.getVehicles.keySet.asScala.toIndexedSeq,
                     beamScenario
                   )}
                   |Number of persons: ${scenario.getPopulation.getPersons.keySet.size}""".stripMargin)
    // check the total count of population required based on the config 'agentSampleSizeAsFractionOfPopulation'
    // required population = agentSampleSizeAsFractionOfPopulation * current population
    val totalPopulationRequired = math.round(
      beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation * scenario.getPopulation.getPersons.size()
    )
    // check the additional population required to be generated (excluding the existing population)
    val additionalPopulationRequired: Long = totalPopulationRequired - scenario.getPopulation.getPersons.size()
    // check the repetitions(of existing population) required to clone the additional population
    val repetitions = math.floor(additionalPopulationRequired / scenario.getPopulation.getPersons.size()).toInt
    // A counter that tracks the number of population in the current scenario (stop as soon as this counter hits the required total population)
    var populationCounter = scenario.getPopulation.getPersons.size()
    val existingHouseHolds = scenario.getHouseholds.getHouseholds.asScala.toSeq
    for (i <- 0 to repetitions) {
      // generate new households
      existingHouseHolds.toStream
        .takeWhile(_ => populationCounter < totalPopulationRequired)
        .map {
          case (houseHoldId, houseHold) =>
            // proceed only if the required population is not yet reached
            // get the members in the current house hold and duplicate them with new ids
            val members =
              houseHold.getMemberIds.asScala
                .flatMap(m => Option(scenario.getPopulation.getPersons.get(m)))
                // proceed only if the required population is not yet reached
                .take((totalPopulationRequired - populationCounter).toInt)
                .map { person =>
                  populationCounter += 1
                  // clone the existing person to create a new person with different id
                  val newPerson =
                    scenario.getPopulation.getFactory.createPerson(Id.createPersonId(s"${person.getId.toString}_$i"))

                  PersonUtils.setSex(newPerson, PersonUtils.getSex(person))
                  PersonUtils.setAge(newPerson, PersonUtils.getAge(person))
                  PersonUtils.setCarAvail(newPerson, PersonUtils.getCarAvail(person))
                  PersonUtils.setLicence(newPerson, PersonUtils.getLicense(person))

                  person.getCustomAttributes
                    .keySet()
                    .forEach(a => {
                      newPerson.getCustomAttributes.put(a, person.getCustomAttributes.get(a))
                    })
                  // copy the plans and attributes of the existing person to the new person
                  person.getPlans.forEach(p => newPerson.addPlan(p))
                  newPerson.setSelectedPlan(person.getSelectedPlan)
                  // add the new person to the scenario
                  scenario.getPopulation.addPerson(newPerson)
                  newPerson.getId
                }
            val vehicles = houseHold.getVehicleIds.asScala
              .flatMap(x => Option(scenario.getVehicles.getVehicles.get(x)))
              .map { vehicle =>
                // clone the current vehicle to form a new vehicle with different id
                val newVehicle = scenario.getVehicles.getFactory
                  .createVehicle(Id.createVehicleId(s"${vehicle.getId.toString}_$i"), vehicle.getType)
                // add the new cloned vehicle to the scenario
                scenario.getVehicles.addVehicle(newVehicle)
                newVehicle.getId
              }
            // generate a new household and add the above clone members and vehicles
            val newHouseHold = scenario.getHouseholds.getFactory
              .createHousehold(Id.create(s"${houseHoldId.toString}_$i", classOf[Household]))
              .asInstanceOf[HouseholdImpl]
            newHouseHold.setIncome(houseHold.getIncome)
            if (members.nonEmpty) newHouseHold.setMemberIds(members.asJava)
            if (vehicles.nonEmpty) newHouseHold.setVehicleIds(vehicles.asJava)
            newHouseHold -> houseHold
        }
        .filter(!_._1.getMemberIds.isEmpty)
        // add the generated new households with attributes to the current scenario
        .foreach {
          case (newhh, oldhh) =>
            scenario.getHouseholds.getHouseholds.put(newhh.getId, newhh)
            Seq("homecoordx", "homecoordy", "housingtype").foreach { attr =>
              val attrValue = scenario.getHouseholds.getHouseholdAttributes.getAttribute(oldhh.getId.toString, attr)
              scenario.getHouseholds.getHouseholdAttributes.putAttribute(newhh.getId.toString, attr, attrValue)
            }
        }
    }
    logger.info(s"""After sampling:
                   |Number of households: ${scenario.getHouseholds.getHouseholds.keySet.size}
                   |Number of vehicles: ${getVehicleGroupingStringUsing(
                     scenario.getVehicles.getVehicles.keySet.asScala.toIndexedSeq,
                     beamScenario
                   )}
                   |Number of persons: ${scenario.getPopulation.getPersons.keySet.size}""".stripMargin)

  }

  def downSample(
    beamServices: BeamServices,
    scenario: MutableScenario,
    beamScenario: BeamScenario,
    outputDir: String
  ): Unit = {
    val beamConfig = beamServices.beamConfig
    val numAgents = math.round(
      beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation * scenario.getPopulation.getPersons.size()
    )
    val rand = new Random(beamServices.beamConfig.matsim.modules.global.randomSeed)
    val notSelectedHouseholdIds = mutable.Set[Id[Household]]()
    val notSelectedVehicleIds = mutable.Set[Id[Vehicle]]()
    val notSelectedPersonIds = mutable.Set[Id[Person]]()

    // We add all households, vehicles and persons to the sets
    scenario.getHouseholds.getHouseholds.values().asScala.foreach { hh =>
      hh.getVehicleIds.forEach(vehicleId => notSelectedVehicleIds.add(vehicleId))
    }
    scenario.getHouseholds.getHouseholds
      .keySet()
      .forEach(householdId => notSelectedHouseholdIds.add(householdId))
    scenario.getPopulation.getPersons
      .keySet()
      .forEach(personId => notSelectedPersonIds.add(personId))

    logger.info(s"""Before sampling:
                   |Number of households: ${notSelectedHouseholdIds.size}
                   |Number of vehicles: ${getVehicleGroupingStringUsing(
                     notSelectedVehicleIds.toIndexedSeq,
                     beamScenario
                   )}
                   |Number of persons: ${notSelectedPersonIds.size}""".stripMargin)

    val iterHouseholds = rand.shuffle(scenario.getHouseholds.getHouseholds.values().asScala).iterator
    var numberOfAgents = 0
    // We start from the first household and remove its vehicles and persons from the sets to clean
    while (numberOfAgents < numAgents && iterHouseholds.hasNext) {

      val household = iterHouseholds.next()
      numberOfAgents += household.getMemberIds.size()
      household.getVehicleIds.forEach(vehicleId => notSelectedVehicleIds.remove(vehicleId))
      notSelectedHouseholdIds.remove(household.getId)
      household.getMemberIds.forEach(persondId => notSelectedPersonIds.remove(persondId))
    }

    // Remove not selected vehicles
    notSelectedVehicleIds.foreach { vehicleId =>
      scenario.getVehicles.removeVehicle(vehicleId)
      beamScenario.privateVehicles.remove(vehicleId)
    }

    // Remove not selected households
    notSelectedHouseholdIds.foreach { housholdId =>
      scenario.getHouseholds.getHouseholds.remove(housholdId)
      scenario.getHouseholds.getHouseholdAttributes.removeAllAttributes(housholdId.toString)
    }

    // Remove not selected persons
    notSelectedPersonIds.foreach { personId =>
      scenario.getPopulation.removePerson(personId)
    }

    writeScenarioPrivateVehicles(scenario, beamScenario, outputDir)

    val numOfHouseholds = scenario.getHouseholds.getHouseholds.values().size
    val vehicles = scenario.getHouseholds.getHouseholds.values.asScala.flatMap(hh => hh.getVehicleIds.asScala)
    val numOfPersons = scenario.getPopulation.getPersons.size()

    logger.info(s"""After sampling:
                   |Number of households: $numOfHouseholds. Removed: ${notSelectedHouseholdIds.size}
                   |Number of vehicles: ${getVehicleGroupingStringUsing(vehicles.toIndexedSeq, beamScenario)}. Removed: ${getVehicleGroupingStringUsing(
                     notSelectedVehicleIds.toIndexedSeq,
                     beamScenario
                   )}
                   |Number of persons: $numOfPersons. Removed: ${notSelectedPersonIds.size}""".stripMargin)

  }

  def sampleByIndustry(scenario: MutableScenario, beamConfig: BeamConfig): Unit = {
    val industryFile = beamConfig.beam.agentsim.agents.population.industryRemovalProbabilty.inputFilePath
    val industrialProbability: Map[String, Double] = {
      if (new File(industryFile).exists()) {
        val (iter: Iterator[mutable.Map[String, String]], toClose: Closeable) =
          GenericCsvReader.readAs[mutable.Map[String, String]](industryFile, rec => rec.asScala, _ => true)
        try {
          iter.map(value => value("industry") -> value("removal_probability").toDouble).toMap
        } finally {
          toClose.close()
        }
      } else Map.empty
    }
    val selectedPersons = getSelectedPersons(
      beamConfig.matsim.modules.global.randomSeed,
      scenario.getPopulation.getPersons.values().asScala,
      industrialProbability
    )
    logger.info(s"Selected ${selectedPersons.length} out of ${scenario.getPopulation.getPersons.size()} people")

    beamConfig.beam.agentsim.agents.population.industryRemovalProbabilty.removalStrategy match {
      case "RemovePersonFromScenario" =>
        removePeople(scenario, selectedPersons)
      case "KeepPersonButRemoveAllActivities" =>
        val nPeopleWithWorkingActivitiesBefore = getNumberOfWorkingActivities(scenario)
        logger.info(
          s"Before plan removal. $nPeopleWithWorkingActivitiesBefore out ${scenario.getPopulation.getPersons.size()} have working activity"
        )
        removeWorkPlan(selectedPersons)
        val nPeopleWithWorkingActivitiesAfter = getNumberOfWorkingActivities(scenario)
        logger.info(
          s"After plan removal. $nPeopleWithWorkingActivitiesAfter out of ${scenario.getPopulation.getPersons.size()} have working activity"
        )
      case x =>
        logger.warn(
          s"Don't know beam.agentsim.agents.population.industryRemovalProbabilty.removalStrategy=${beamConfig.beam.agentsim.agents.population.industryRemovalProbabilty.removalStrategy}"
        )
    }
  }

  def removePeople(scenario: MutableScenario, peopleToRemove: Iterable[Person]): Unit = {
    val memberIdToHousehold = scenario.getHouseholds.getHouseholds
      .values()
      .asScala
      .flatMap(hh => hh.getMemberIds.asScala.map(memberId => memberId -> hh.getId))
      .toMap

    var nRemoved: Int = 0
    peopleToRemove.foreach { person =>
      val personId: Id[Person] = person.getId
      memberIdToHousehold.get(personId) match {
        case Some(hhId) =>
          val hh = scenario.getHouseholds.getHouseholds.get(hhId).asInstanceOf[HouseholdImpl]
          val members = hh.getMemberIds.asScala.filter(pId => pId != personId).asJava
          hh.setMemberIds(members)
        case _ =>
          logger.error(s"Household for person $personId is missing.")
      }
      if (Option(scenario.getPopulation.getPersons.remove(personId)).nonEmpty) {
        nRemoved += 1
      }
    }

    logger.info(
      s"Removing done, removed $nRemoved persons, new population size: ${scenario.getPopulation.getPersons.size()}"
    )
  }

  def getSelectedPersons(
    rndSeed: Int,
    persons: Iterable[Person],
    industrialProbability: Map[String, Double]
  ): Array[Person] = {
    def probabilityFunction(person: Person): Double = {
      industrialProbability.getOrElse(getIndustry(person), 0.0)
    }
    val selectedPersons = MathUtils.selectElementsByProbability(rndSeed, probabilityFunction, persons)
    selectedPersons
  }

  def getIndustry(person: Person): String = {
    val industryAttribute = person.getAttributes.getAttribute("industry")
    if (industryAttribute != null) industryAttribute.toString else ""
  }

  def removeWorkPlan(persons: Iterable[Person]): Unit = {
    var nRemovedWorkPlans: Int = 0
    persons.foreach { person: Person =>
      val originalPlan = person.getSelectedPlan
      val planElements = originalPlan.getPlanElements.asScala
      if (planElements.exists(isWorkActivity)) {
        //Keep only first activity of day
        val daysFirstActivity = planElements.head.asInstanceOf[Activity]
        val newPlan = PopulationUtils.createPlan(originalPlan.getPerson)
        daysFirstActivity.setEndTime(Double.NegativeInfinity)
        newPlan.addActivity(daysFirstActivity)
        person.addPlan(newPlan)
        person.removePlan(originalPlan)
        person.setSelectedPlan(newPlan)
        nRemovedWorkPlans += 1
      }
    }
    logger.info(s"Removed $nRemovedWorkPlans working plans from ${persons.size} people")
  }

  def isWorkActivity(plan: PlanElement): Boolean = {
    plan match {
      case activity: Activity =>
        activity.getType.toLowerCase() == "work"
      case _ =>
        false
    }
  }

  private def getVehicleGroupingStringUsing(vehicleIds: IndexedSeq[Id[Vehicle]], beamScenario: BeamScenario): String = {
    vehicleIds
      .groupBy(
        vehicleId => beamScenario.privateVehicles.get(vehicleId).map(_.beamVehicleType.id.toString).getOrElse("")
      )
      .map {
        case (vehicleType, ids) => s"$vehicleType (${ids.size})"
      }
      .mkString(" , ")
  }

  private def writeScenarioPrivateVehicles(
    scenario: MutableScenario,
    beamServices: BeamScenario,
    outputDir: String
  ): Unit = {
    new FileWriter(outputDir + "/householdVehicles.csv", true).use { csvWriter =>
      csvWriter.write("vehicleId,vehicleType,householdId\n")
      scenario.getHouseholds.getHouseholds.values.asScala.foreach { householdId =>
        householdId.getVehicleIds.asScala.foreach { vehicle =>
          beamServices.privateVehicles
            .get(vehicle)
            .map(
              v => v.id.toString + "," + v.beamVehicleType.id.toString + "," + householdId.getId.toString + "\n"
            )
            .foreach(csvWriter.write)
        }
      }
    }
  }

  private def getNumberOfWorkingActivities(scenario: MutableScenario): Int = {
    scenario.getPopulation.getPersons.values().asScala.count { p =>
      p.getSelectedPlan.getPlanElements.asScala.exists(isWorkActivity)
    }
  }

}

object PopulationScaling {

  def isWarmstartDisabledOrSamplingEnabled(beamConfig: BeamConfig): Boolean = {
    !beamConfig.beam.warmStart.enabled || beamConfig.beam.warmStart.samplePopulationIntegerFlag == 1
  }

  // sample population (beamConfig.beam.agentsim.numAgents - round to nearest full household)
  def samplePopulation(
    scenario: MutableScenario,
    beamScenario: BeamScenario,
    beamConfig: BeamConfig,
    beamServices: BeamServices,
    outputDir: String
  ): Unit = {
    val populationScaling = new PopulationScaling()
    if (isWarmstartDisabledOrSamplingEnabled(beamConfig) && beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation < 1) {
      populationScaling.downSample(beamServices, scenario, beamScenario, outputDir)
    }
    if (isWarmstartDisabledOrSamplingEnabled(beamConfig) && beamConfig.beam.agentsim.agentSampleSizeAsFractionOfPopulation > 1) {
      populationScaling.upSample(beamServices, scenario, beamScenario)
    }
    val populationAdjustment = PopulationAdjustment.getPopulationAdjustment(beamServices)
    populationAdjustment.update(scenario)

    // write static metrics, such as population size, vehicles fleet size, etc.
    // necessary to be called after population sampling
    BeamStaticMetricsWriter.writeSimulationParameters(
      scenario,
      beamScenario,
      beamServices,
      beamConfig
    )
  }
}
