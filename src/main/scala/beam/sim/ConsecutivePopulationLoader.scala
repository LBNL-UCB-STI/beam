package beam.sim

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.{Person, Population, PopulationFactory}
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{
  Household,
  HouseholdImpl,
  HouseholdUtils,
  Households,
  HouseholdsFactoryImpl,
  HouseholdsImpl
}
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.utils.objectattributes.attributable.AttributesUtils

import scala.collection.mutable
import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.util.Random

class ConsecutivePopulationLoader(
  val scenario: Scenario,
  val percentToSimulate: Array[Double],
  val javaRnd: java.util.Random
) extends StrictLogging {
  private val rnd: Random = new Random(javaRnd)

  private val totalSum: Double = percentToSimulate.sum
  if (Math.abs(totalSum - 100) >= 0.01)
    throw new IllegalStateException(
      s"The sum of $percentToSimulate [${percentToSimulate.mkString(" ")}] is $totalSum, but it should be 100"
    )

  private val fullPopulation: Population = {
    val newPop = PopulationUtils.createPopulation(scenario.getConfig)
    copyPopulation(scenario.getPopulation, newPop)
    newPop
  }

  private val fullPopulationPersons = fullPopulation.getPersons

  private val personIdToHousehold: Map[Id[Person], Household] = scenario.getHouseholds.getHouseholds
    .values()
    .asScala
    .flatMap { hh =>
      hh.getMemberIds.asScala.map(p => p -> hh)
    }
    .toMap
    .seq

  private val fullHousehold: HouseholdsImpl = {
    val newHouseholds: HouseholdsImpl = new HouseholdsImpl()
    copyHouseholds(scenario.getHouseholds, newHouseholds, new HouseholdsFactoryImpl())
    newHouseholds
  }

  private val peopleWhichCanBeTaken: mutable.Set[Person] = {
    val canTake = fullPopulation.getPersons.values().asScala
    mutable.HashSet[Person](canTake.toSeq: _*)
  }

  val numberOfPeopleToSimulateEveryIter: Array[Int] = {
    val xs = percentToSimulate.map(p => (peopleWhichCanBeTaken.size * p / 100).toInt)
    if (xs.sum != peopleWhichCanBeTaken.size) {
      val leftDueToRounding = peopleWhichCanBeTaken.size - xs.sum
      logger.debug(s"leftDueToRounding: $leftDueToRounding")
      xs(xs.length - 1) = xs(xs.length - 1) + leftDueToRounding
    }
    xs
  }
  logger.info(s"% to simulate per iteration: ${percentToSimulate.mkString(" ")}")
  logger.info(s"Number of people to simulate per iteration: ${numberOfPeopleToSimulateEveryIter.mkString(" ")}")

  logger.info(
    s"Cumulative % to simulate per iteration: ${percentToSimulate.scanLeft(0.0)(_ + _).drop(1).mkString(" ")}"
  )
  logger.info(
    s"Cumulative number of people to simulate per iteration: ${numberOfPeopleToSimulateEveryIter.scanLeft(0)(_ + _).drop(1).mkString(" ")}"
  )

  private var iterationNumber: Int = 0

  def load(): Unit = {
    if (iterationNumber < numberOfPeopleToSimulateEveryIter.length) {
      val numberOfPeopleToTake = numberOfPeopleToSimulateEveryIter(iterationNumber)
      val setOfPeople = getNextSetOfPeople(peopleWhichCanBeTaken, rnd, numberOfPeopleToTake)
      logger.info(s"setOfPeople size is ${setOfPeople.size}. numberOfPeopleToTake: $numberOfPeopleToTake")
      setOfPeople.foreach { personToAdd =>
        // Remove them from original set
        peopleWhichCanBeTaken.remove(personToAdd)
        // Add to the population
        scenario.getPopulation.addPerson(personToAdd)

        val attributes = fullPopulationPersons.get(personToAdd.getId).getCustomAttributes
        copyPersonAttribute(attributes, personToAdd, "excluded-modes")

        copyPersonAttribute(attributes, personToAdd, "rank")
        copyPersonAttribute(attributes, personToAdd, "valueOfTime")

        personIdToHousehold.get(personToAdd.getId).foreach { hh =>
          val map = scenario.getHouseholds.getHouseholds
          Option(map.get(hh.getId)) match {
            case Some(_) =>
              // If household is already added, we just need to add person to this household because originally it belongs to it
              hh.getMemberIds.add(personToAdd.getId)
            case None =>
              // Put the household to the map and add the first person of that household
              map.put(hh.getId, hh)
              copyHouseholdAttributes(
                fullHousehold.getHouseholds.get(hh.getId),
                scenario.getHouseholds.getHouseholds.get(hh.getId)
              )
              hh.asInstanceOf[HouseholdImpl].setMemberIds(ArrayBuffer(personToAdd.getId).asJava)
          }
        }
      }
      logger.info(s"Population size on iteration $iterationNumber is ${scenario.getPopulation.getPersons.size()}")
      iterationNumber += 1
    }
  }

  def getNextSetOfPeople(
    xs: collection.Set[Person],
    rnd: Random,
    numberOfPeopleToTake: Int
  ): collection.Set[Person] = {
    val takeN = if (numberOfPeopleToTake > xs.size) xs.size else numberOfPeopleToTake
    val next = rnd.shuffle(xs).take(takeN)
    next
  }

  def cleanScenario(): Unit = {
    val population = scenario.getPopulation
    val households = scenario.getHouseholds
    val peopleToRemove = population.getPersons.keySet().asScala.toVector
    peopleToRemove.foreach { personId =>
      population.removePerson(personId)

      personIdToHousehold.get(personId).foreach { hh =>
        hh.asInstanceOf[HouseholdImpl].setMemberIds(java.util.Collections.emptyList())
        households.getHouseholds.remove(hh.getId)
        households.getHouseholds.get(hh.getId).getAttributes.clear()
      }
    }
  }

  private def copyPopulation(src: Population, dest: Population): Unit = {
    src.getPersons.values.asScala.foreach { person =>
      val copiedPerson: Person = createCopyOfPerson(person, dest.getFactory)
      dest.addPerson(copiedPerson)
      copyPersonAttributes(src.getPersons.get(person).getCustomAttributes, person)

      person.getCustomAttributes.forEach((k, v) => copiedPerson.getCustomAttributes.put(k, v))
    }
  }

  private def copyPersonAttributes(src: java.util.Map[String, Object], person: Person): Unit = {
    copyPersonAttribute(src, person, "excluded-modes")
    copyPersonAttribute(src, person, "rank")
    copyPersonAttribute(src, person, "valueOfTime")
  }

  private def copyPersonAttribute(
    srcPersonAttributes: java.util.Map[String, Object],
    person: Person,
    name: String
  ): Unit = {
    val personIdStr = person.toString
    val attribValue = srcPersonAttributes.get(name)
    if (attribValue != null) {
      PopulationUtils.putPersonAttribute(person, name, attribValue)
    }
  }

  private def createCopyOfPerson(srcPerson: Person, factory: PopulationFactory) = {
    val person = factory.createPerson(srcPerson.getId)
    val plan = factory.createPlan
    PopulationUtils.copyFromTo(srcPerson.getSelectedPlan, plan)
    person.addPlan(plan)
    person.setSelectedPlan(plan)
    AttributesUtils.copyAttributesFromTo(srcPerson, person)
    person
  }

  private def copyHouseholds(src: Households, dest: Households, factory: HouseholdsFactoryImpl): Unit = {
    src.getHouseholds.values().asScala.foreach { hh =>
      val household = factory.createHousehold(hh.getId)
      household.setIncome(hh.getIncome)
      // We shouldn't set the members because during the consecutive population increase there is a chance that some of the people of household will not loaded in this iteration
      // household.setMemberIds(hh.getMemberIds)
      household.setVehicleIds(hh.getVehicleIds)
      copyHouseholdAttributes(hh, dest.getHouseholds.get(hh.getId))
      dest.getHouseholds.put(hh.getId, household)
    }
  }

  private def copyHouseholdAttributes(
    src: Household,
    dest: Household
  ): Unit = {
    copyHouseholdAttribute(src, dest, "homecoordx")
    copyHouseholdAttribute(src, dest, "homecoordy")
    copyHouseholdAttribute(src, dest, "housingtype")
  }

  private def copyHouseholdAttribute(
    srcHousehold: Household,
    destHousehold: Household,
    attribName: String
  ): Unit = {
    val srcAttribVal = HouseholdUtils.getHouseholdAttribute(srcHousehold, attribName)
    HouseholdUtils.putHouseholdAttribute(destHousehold, attribName, srcAttribVal)
  }
}
