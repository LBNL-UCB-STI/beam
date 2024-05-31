package beam.sim.population

import beam.sim.BeamScenario
import ch.qos.logback.classic.Level
import ch.qos.logback.classic.Level.{ERROR, INFO}
import org.matsim.api.core.v01.population.{Person, Plan, Population, PopulationFactory}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.core.population.PopulationUtils
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.utils.objectattributes.attributable.Attributes
import org.scalatest.BeforeAndAfterEach
import org.scalatest.matchers.should.Matchers
import org.scalatest.tagobjects.Retryable
import org.scalatest.wordspec.AnyWordSpec

import java.util
import scala.collection.JavaConverters._
import scala.collection.mutable

class PopulationAdjustmentSpec extends AnyWordSpec with Matchers with BeforeAndAfterEach {

  "PopulationAdjustment" should {
    "add logs to created appender" in {
      val popAdj = new TestPopulationAdjustment()
      val testLogEntry = "Log entry for testing appender."
      popAdj.logInfo(testLogEntry)
      popAdj.verifyLogging(INFO -> testLogEntry)
    }

    "logs excluded modes" taggedAs Retryable in {
      val popAdj = new TestPopulationAdjustment()
      val personWithAttributes = createPersons.mapValues { person =>
        val id = person.getId.toString.toInt
        // bike is excluded for 2 persons
        // car is excluded for 5 persons
        val excludedModes = (id % 5, id % 2) match {
          case (0, 0) => "bike,car"
          case (0, _) => "bike"
          case (_, 0) => "car"
          case (_, _) => ""
        }
        PopulationUtils.putPersonAttribute(person, PopulationAdjustment.EXCLUDED_MODES, excludedModes)
        person
      }
      val population = createPopulation(personWithAttributes)

      popAdj.logModes(population)
      popAdj.verifyLogging(INFO -> "Modes excluded:", INFO -> "car -> 5", INFO -> "bike -> 2")
    }

    "logs excluded modes defined as iterable" in {
      val popAdj = new TestPopulationAdjustment()
      val personWithAttributes = createPersons.mapValues { person =>
        val id = person.getId.toString.toInt
        // bike is excluded for 5 persons
        // car is excluded for 2 persons
        val excludedModes = (id % 2, id % 5) match {
          case (0, 0) => List("bike", "car")
          case (0, _) => mutable.WrappedArray.make(Array("bike"))
          case (_, 0) => mutable.Buffer("car")
          case (_, _) => Set.empty
        }
        PopulationUtils.putPersonAttribute(person, PopulationAdjustment.EXCLUDED_MODES, excludedModes)
        person
      }
      val population = createPopulation(personWithAttributes)

      popAdj.logModes(population)
      popAdj.verifyLogging(INFO -> "Modes excluded:", INFO -> "bike -> 5", INFO -> "car -> 2")
    }

    "logs excluded modes and alarms not all persons have required attribute" in {
      val popAdj = new TestPopulationAdjustment()
      val personWithAttributes = createPersons.mapValues { person =>
        val id = person.getId.toString.toInt
        // bike is excluded for 2 persons
        // car is excluded for 3 persons
        val excludedModes = (id % 5, id % 3) match {
          case (0, 0) => Some("bike,car")
          case (0, _) => Some("bike")
          case (_, 0) => Some("car")
          case (_, _) => None
        }
        excludedModes.foreach { mode =>
          PopulationUtils.putPersonAttribute(person, PopulationAdjustment.EXCLUDED_MODES, mode)
        }
        person
      }
      val population = createPopulation(personWithAttributes)

      popAdj.logModes(population)
      popAdj.verifyLogging(
        INFO  -> "Modes excluded:",
        INFO  -> "car -> 3",
        INFO  -> "bike -> 2",
        ERROR -> "Not all agents have person attributes - is attributes file missing ?"
      )
    }
  }

  case class LogEntry(message: String, level: Level)

  object LogEntry {

    def InfoMessage(message: String): LogEntry = {
      new LogEntry(message, level = INFO)
    }

    def ErrorMessage(message: String): LogEntry = {
      new LogEntry(message, level = ERROR)
    }
  }

  class TestPopulationAdjustment extends PopulationAdjustment {
    override lazy val scenario: Scenario = ???
    override lazy val beamScenario: BeamScenario = ???
    override def updatePopulation(scenario: Scenario): Population = ???

    private val log: mutable.MutableList[LogEntry] = mutable.MutableList.empty[LogEntry]

    override def logInfo(message: String): Unit = {
      log += LogEntry.InfoMessage(message)
    }

    override def logError(message: String): Unit = {
      log += LogEntry.ErrorMessage(message)
    }

    def verifyLogging(expectedLogs: (Level, String)*): Unit = {
      log.map(e => e.level -> e.message) shouldBe expectedLogs
    }

  }

  private def createPersons: Map[Id[Person], Person] =
    (1L to 10L)
      .map(Id.createPersonId)
      .map(id => (id, createPerson(id)))
      .toMap

  private def createPerson(id: Id[Person]): Person = new Person {
    private val attributes = new Attributes

    override def getCustomAttributes: util.Map[String, AnyRef] = ???
    override def getPlans: util.List[_ <: Plan] = ???
    override def addPlan(p: Plan): Boolean = ???
    override def removePlan(p: Plan): Boolean = ???
    override def getSelectedPlan: Plan = ???
    override def setSelectedPlan(selectedPlan: Plan): Unit = ???
    override def createCopyOfSelectedPlanAndMakeSelected(): Plan = ???
    override def getAttributes: Attributes = attributes
    override def getId: Id[Person] = id
  }

  private def createPopulation(persons: Map[Id[Person], Person]): Population = new Population() {
    private val personAttributes = new ObjectAttributes
    private val attributes = new Attributes

    override def getFactory: PopulationFactory = ???
    override def getName = "population written from testing"
    override def setName(name: String): Unit = ???
    override def getPersons: util.Map[Id[Person], Person] = persons.asJava
    override def addPerson(p: Person): Unit = ???
    override def removePerson(personId: Id[Person]): Person = ???
    override def getAttributes: Attributes = attributes
  }
}
