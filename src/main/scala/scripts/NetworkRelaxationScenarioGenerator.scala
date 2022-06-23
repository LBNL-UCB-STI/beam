package scripts

import beam.utils.csv.CsvWriter
import beam.utils.scenario.urbansim.censusblock.entities._
import java.io.File
import scala.util.Random

class NetworkRelaxationScenarioGenerator {
  val scenarioDir = new File("test/input/network-relaxation-scenario/urbansim_v2")

  def generateBlocks() = {
    println(s"Generating blocks")
    val csvWriter = new CsvWriter(
      scenarioDir + "/blocks.csv.gz",
      Seq("block_id", "x", "y")
    )
    csvWriter.write("1", "166648.21039213781", "998.9706301352383")
    csvWriter.close()
  }

  def generateHouseholds(count: Int = 2000) = {
    println(s"Generating $count households")
    val csvWriter = new CsvWriter(
      scenarioDir + "/households.csv.gz",
      Seq("household_id", "income", "cars", "block_id")
    )
    try {
      for (i <- 1 to count) {
        csvWriter.write(i.toString, "0", "1", "1")
      }
    } finally {
      csvWriter.close()
    }

  }

  def generatePersons(count: Int = 2000) = {
    println(s"Generating $count persons")
    val persons = for (i <- 1 to count) yield {
      InputPersonInfo(
        personId = i.toString,
        householdId = i.toString,
        age = 20 + Random.nextInt(50),
        sex = if (Random.nextBoolean()) Male else Female,
        None
      )
    }
    val csvWriter =
      new CsvWriter(scenarioDir.getPath + "/persons.csv.gz", Seq("person_id", "household_id", "age", "sex"))
    try {
      persons.foreach { person =>
        csvWriter.write(
          person.personId,
          person.householdId,
          person.age,
          person.sex match {
            case Male   => 1
            case Female => 2
          }
        )
      }
    } finally {
      csvWriter.close()
    }
  }

  def generatePlans(count: Int = 2000) = {
    println("Generating plans")
    val headers = Seq(
      "trip_id",
      "person_id",
      "PlanElementIndex",
      "ActivityElement",
      "trip_mode",
      "ActivityType",
      "x",
      "y",
      "departure_time"
    )
    val plans = for (i <- 1 to count) yield {
      // Home = -0.03763798759, 0.00975476975
      // Work = 0.04874384139, 0.01013286711
      Seq(
        InputPlanElement(
          tripId = Some(i.toString),
          personId = i.toString,
          planElementIndex = 1,
          activityElement = Activity,
          tripMode = None,
          ActivityType = Some("Home"),
          x = Some(161827.4228835071),
          y = Some(1079.7224574150498),
          departureTime = Some(8 + Random.nextDouble() * 4)
        ),
        InputPlanElement(
          tripId = Some(i.toString),
          personId = i.toString,
          planElementIndex = 2,
          activityElement = Leg,
          tripMode = Some("CAR"),
          ActivityType = None,
          x = None,
          y = None,
          departureTime = None
        ),
        InputPlanElement(
          tripId = Some(i.toString),
          personId = i.toString,
          planElementIndex = 3,
          activityElement = Activity,
          tripMode = None,
          ActivityType = Some("Work"),
          x = Some(171452.789949885),
          y = Some(1121.4837267244413),
          departureTime = None
        )
      )
    }
    val csvWriter = new CsvWriter(scenarioDir.getPath + "/plans.csv.gz", headers)
    try {
      plans.flatten.foreach { plan =>
        csvWriter.write(
          plan.tripId.mkString,
          plan.personId.mkString,
          plan.planElementIndex,
          plan.activityElement,
          plan.tripMode,
          plan.ActivityType,
          plan.x,
          plan.y,
          plan.departureTime
        )
      }
    } finally {
      csvWriter.close()
    }
  }

  def generate() = {
    scenarioDir.mkdir()
    generateBlocks()
    generateHouseholds()
    generatePersons()
    generatePlans()
  }
}

object NetworkRelaxationScenarioGenerator extends App {
  new NetworkRelaxationScenarioGenerator().generate()
}
