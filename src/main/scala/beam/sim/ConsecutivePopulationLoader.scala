package beam.sim

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.api.core.v01.population.{Person, Population, PopulationFactory}
import org.matsim.core.population.PopulationUtils
import org.matsim.households.{Household, HouseholdImpl, Households, HouseholdsFactoryImpl, HouseholdsImpl}
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
        copyPeronAttribute(
          fullPopulation.getPersonAttributes,
          scenario.getPopulation.getPersonAttributes,
          personToAdd.getId,
          "excluded-modes"
        )
        copyPeronAttribute(
          fullPopulation.getPersonAttributes,
          scenario.getPopulation.getPersonAttributes,
          personToAdd.getId,
          "rank"
        )
        copyPeronAttribute(
          fullPopulation.getPersonAttributes,
          scenario.getPopulation.getPersonAttributes,
          personToAdd.getId,
          "valueOfTime"
        )

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
                fullHousehold.getHouseholdAttributes,
                scenario.getHouseholds.getHouseholdAttributes,
                hh.getId
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
      population.getPersonAttributes.removeAllAttributes(personId.toString)

      personIdToHousehold.get(personId).foreach { hh =>
        hh.asInstanceOf[HouseholdImpl].setMemberIds(java.util.Collections.emptyList())
        households.getHouseholds.remove(hh.getId)
        households.getHouseholdAttributes.removeAllAttributes(hh.getId.toString)
      }
    }
  }

  private def copyPopulation(src: Population, dest: Population): Unit = {
    src.getPersons.values.asScala.foreach { person =>
      val copiedPerson: Person = createCopyOfPerson(person, dest.getFactory)
      dest.addPerson(copiedPerson)
      copyPeronAttributes(src.getPersonAttributes, dest.getPersonAttributes, person.getId)

      person.getCustomAttributes.forEach((k, v) => copiedPerson.getCustomAttributes.put(k, v))
    }
  }

  private def copyPeronAttributes(src: ObjectAttributes, dest: ObjectAttributes, person: Id[Person]): Unit = {
    copyPeronAttribute(src, dest, person, "excluded-modes")
    copyPeronAttribute(src, dest, person, "rank")
    copyPeronAttribute(src, dest, person, "valueOfTime")
  }

  private def copyPeronAttribute(
    srcPersonAttributes: ObjectAttributes,
    dstPersonAttributes: ObjectAttributes,
    person: Id[Person],
    name: String
  ): Unit = {
    val personIdStr = person.toString
    val attribValue = srcPersonAttributes.getAttribute(personIdStr, name)
    if (attribValue != null) {
      dstPersonAttributes.putAttribute(personIdStr, name, attribValue)

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
      copyHouseholdAttributes(src.getHouseholdAttributes, dest.getHouseholdAttributes, hh.getId)
      dest.getHouseholds.put(hh.getId, household)
    }
  }

  private def copyHouseholdAttributes(
    src: ObjectAttributes,
    dest: ObjectAttributes,
    householdId: Id[Household]
  ): Unit = {
    copyHouseholdAttribute(src, dest, householdId, "homecoordx")
    copyHouseholdAttribute(src, dest, householdId, "homecoordy")
    copyHouseholdAttribute(src, dest, householdId, "housingtype")
  }

  private def copyHouseholdAttribute(
    srcHouseholdAttributes: ObjectAttributes,
    destHouseholdAttributes: ObjectAttributes,
    householdId: Id[Household],
    name: String
  ): Unit = {
    val personIdStr = householdId.toString
    val attribValue = srcHouseholdAttributes.getAttribute(personIdStr, name)
    destHouseholdAttributes.putAttribute(personIdStr, name, attribValue)
  }
}
