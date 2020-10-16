package beam.sim.population

import java.util

import beam.sim.BeamScenario
import ch.qos.logback.classic.Level.{ERROR, INFO}
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger}
import ch.qos.logback.core.read.ListAppender
import org.matsim.api.core.v01.population.{Person, Plan, Population, PopulationFactory}
import org.matsim.api.core.v01.{Id, Scenario}
import org.matsim.utils.objectattributes.ObjectAttributes
import org.matsim.utils.objectattributes.attributable.Attributes
import org.scalatest.{BeforeAndAfterEach, Matchers, WordSpecLike}
import org.scalatestplus.mockito.MockitoSugar
import org.slf4j.LoggerFactory

import scala.collection.JavaConverters._
import scala.collection.mutable

class PopulationAdjustmentSpec extends WordSpecLike with MockitoSugar with Matchers with BeforeAndAfterEach {

  private val appLogger = LoggerFactory.getLogger(TestPopulationAdjustment.getClass.getName).asInstanceOf[Logger]
  private var appender: ListAppender[ILoggingEvent] = _

  override def beforeEach(): Unit = {
    appender = new ListAppender[ILoggingEvent]
    appender.start()
    appLogger.addAppender(appender)
  }

  override def afterEach(): Unit = {
    appLogger.detachAppender(appender)
  }

  "PopulationAdjustment" should {
    "logs excluded modes defined as strings" in {
      val population = createPopulation(persons)
      persons.keys.map(_.toString.toInt).foreach { id =>
        // bike is excluded for 2 persons
        // car is excluded for 5 persons
        val excludedModes = (id % 5, id % 2) match {
          case (0, 0) => "bike,car"
          case (0, _) => "bike"
          case (_, 0) => "car"
          case (_, _) => ""
        }
        population.getPersonAttributes.putAttribute(id.toString, PopulationAdjustment.EXCLUDED_MODES, excludedModes)
      }

      TestPopulationAdjustment.logModes(population)
      verifyLogging(
        INFO -> "Modes excluded:",
        INFO -> "bike -> 2",
        INFO -> "car -> 5"
      )
    }

    "logs excluded modes defined as iterable" in {
      val population = createPopulation(persons)
      persons.keys.map(_.toString.toInt).foreach { id =>
        // bike is excluded for 5 persons
        // car is excluded for 2 persons
        val excludedModes = (id % 2, id % 5) match {
          case (0, 0) => List("bike", "car")
          case (0, _) => mutable.WrappedArray.make(Array("bike"))
          case (_, 0) => mutable.Buffer("car")
          case (_, _) => Set.empty
        }
        population.getPersonAttributes.putAttribute(id.toString, PopulationAdjustment.EXCLUDED_MODES, excludedModes)
      }

      TestPopulationAdjustment.logModes(population)
      verifyLogging(
        INFO -> "Modes excluded:",
        INFO -> "bike -> 5",
        INFO -> "car -> 2"
      )
    }

    "logs excluded modes and alarms not all persons have required attribute" in {
      val population = createPopulation(persons)
      persons.keys.map(_.toString.toInt).foreach { id =>
        // bike is excluded for 2 persons
        // car is excluded for 3 persons
        val excludedModes = (id % 5, id % 3) match {
          case (0, 0) => Some("bike,car")
          case (0, _) => Some("bike")
          case (_, 0) => Some("car")
          case (_, _) => None
        }
        excludedModes.foreach { mode =>
          population.getPersonAttributes.putAttribute(id.toString, PopulationAdjustment.EXCLUDED_MODES, mode)
        }
      }

      TestPopulationAdjustment.logModes(population)
      verifyLogging(
        INFO  -> "Modes excluded:",
        INFO  -> "bike -> 2",
        INFO  -> "car -> 3",
        ERROR -> "Not all agents have person attributes - is attributes file missing ?"
      )
    }
  }

  private object TestPopulationAdjustment extends PopulationAdjustment {
    override lazy val scenario: Scenario = ???
    override lazy val beamScenario: BeamScenario = ???
    override def updatePopulation(scenario: Scenario): Population = ???
  }

  private lazy val persons: Map[Id[Person], Person] =
    (1L to 10L)
      .map(Id.createPersonId)
      .map(id => (id, createPerson(id)))
      .toMap

  private def verifyLogging(expectedLogs: (Level, String)*): Unit = {
    appender.list.asScala.map(e => e.getLevel -> e.getFormattedMessage) shouldBe expectedLogs
  }

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
    override def getPersonAttributes: ObjectAttributes = personAttributes
    override def getAttributes: Attributes = attributes
  }
}
