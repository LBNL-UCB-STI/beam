package beam.sim.population

import java.io.FileWriter

import beam.sim.{BeamScenario, BeamServices}
import beam.sim.config.BeamConfig.Beam.Exchange.Scenario
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.core.population.PersonUtils
import org.matsim.core.scenario.MutableScenario
import org.matsim.households.{Household, HouseholdImpl}
import org.matsim.vehicles.Vehicle

import scala.collection.{mutable, JavaConverters}
import scala.util.Random
import scala.collection.JavaConverters._

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
    for (i <- 0 to repetitions) {
      val existingHouseHolds = scenario.getHouseholds.getHouseholds.asScala
      val newHouseHolds: mutable.Map[HouseholdImpl, Household] = existingHouseHolds.flatMap(hh => {
        // proceed only if the required population is not yet reached
        if (populationCounter <= totalPopulationRequired) {
          // get the members in the current house hold and duplicate them with new ids
          val members = hh._2.getMemberIds.asScala
            .flatMap(m => {
              val person: Person = scenario.getPopulation.getPersons.get(m)
              if (person != null) {
                populationCounter += 1
                // proceed only if the required population is not yet reached
                if (populationCounter <= totalPopulationRequired) {
                  // clone the existing person to create a new person with different id
                  val newPerson =
                    scenario.getPopulation.getFactory.createPerson(Id.createPersonId(s"${person.getId.toString}_$i"))
                  Option(person.getAttributes.getAttribute("sex"))
                    .map(a => newPerson.getAttributes.putAttribute("sex", a))
                  Option(person.getAttributes.getAttribute("age"))
                    .map(a => newPerson.getAttributes.putAttribute("age", a))
                  person.getCustomAttributes
                    .keySet()
                    .forEach(a => {
                      newPerson.getCustomAttributes.put(a, person.getCustomAttributes.get(a))
                    })
                  // copy the plans and attributes of the existing person to the new person
                  person.getPlans.forEach(p => newPerson.addPlan(p))
                  PersonUtils.setSex(newPerson, PersonUtils.getSex(person))
                  PersonUtils.setAge(newPerson, PersonUtils.getAge(person))
                  PersonUtils.setCarAvail(newPerson, PersonUtils.getCarAvail(person))
                  PersonUtils.setLicence(newPerson, PersonUtils.getLicense(person))
                  newPerson.setSelectedPlan(person.getSelectedPlan)
                  // add the new person to the scenario
                  scenario.getPopulation.addPerson(newPerson)
                  Some(newPerson.getId)
                } else {
                  None
                }
              } else {
                None
              }
            })
            .toList
          val vehicles = hh._2.getVehicleIds.asScala.flatMap(v => {
            val vehicle: Vehicle = scenario.getVehicles.getVehicles.get(v)
            if (vehicle != null) {
              // clone the current vehicle to form a new vehicle with different id
              val newVehicle = scenario.getVehicles.getFactory
                .createVehicle(Id.createVehicleId(s"${vehicle.getId.toString}_$i"), vehicle.getType)
              // add the new cloned vehicle to the scenario
              scenario.getVehicles.addVehicle(newVehicle)
              Some(newVehicle.getId)
            } else {
              None
            }
          })
          // generate a new household and add the above clone members and vehicles
          val newHouseHold = scenario.getHouseholds.getFactory
            .createHousehold(Id.create(s"${hh._1.toString}_$i", classOf[Household]))
            .asInstanceOf[HouseholdImpl]
          newHouseHold.setIncome(hh._2.getIncome)
          if (members.nonEmpty) {
            newHouseHold.setMemberIds(JavaConverters.seqAsJavaList(members))
          }
          if (vehicles.nonEmpty) {
            newHouseHold.setVehicleIds(JavaConverters.seqAsJavaList(vehicles))
          }
          Some(newHouseHold -> hh._2)
        } else {
          None
        }
      })
      // add the generated new households with attributes to the current scenario
      newHouseHolds foreach {
        case (newhh, oldhh) =>
          scenario.getHouseholds.getHouseholds.put(newhh.getId, newhh)
          for (attr <- List("homecoordx", "homecoordy", "housingtype")) {
            val attrValue = scenario.getHouseholds.getHouseholdAttributes.getAttribute(oldhh.getId.toString, attr)
            scenario.getHouseholds.getHouseholdAttributes.putAttribute(newhh.getId.toString, attr, attrValue)
          }
      }
    }
    // reset the counter
    populationCounter = 0
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
    val csvWriter: FileWriter = new FileWriter(outputDir + "/householdVehicles.csv", true)
    try {
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
    } finally {
      csvWriter.close()
    }
  }

}
