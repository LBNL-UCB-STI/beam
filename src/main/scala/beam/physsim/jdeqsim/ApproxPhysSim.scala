package beam.physsim.jdeqsim

import beam.router.r5.R5Parameters
import beam.sim.config.BeamConfig
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.utils.Statistics
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population._
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.population.PopulationUtils
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.misc.Time
import org.matsim.utils.objectattributes.attributable.AttributesUtils

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.util.{Random, Try}

class ApproxPhysSim(
  val beamConfig: BeamConfig,
  val agentSimScenario: Scenario,
  val population: Population,
  val beamServices: BeamServices,
  val controllerIO: OutputDirectoryHierarchy,
  val isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  val beamConfigChangesObservable: BeamConfigChangesObservable,
  val agentSimIterationNumber: Int,
  val shouldWritePhysSimEvents: Boolean,
  val javaRnd: java.util.Random,
  val percentToSimulate: Array[Double]
) extends StrictLogging {
  val totalSum: Double = percentToSimulate.sum
  if (Math.abs(totalSum - 100) >= 0.01)
    throw new IllegalStateException(
      s"The sum of $percentToSimulate [${percentToSimulate.mkString(" ")}] is $totalSum, but it should be 100"
    )

  val finalPopulation: Population = initPopulation(population)

  val peopleWhichCanBeTaken: mutable.Set[Person] = {
    val buses = getBuses(population).toSet
    val canTake = population.getPersons.values().asScala.filter(p => !buses.contains(p))
    mutable.HashSet[Person](canTake.toSeq: _*)
  }

  val numberOfPeopleToSimulateEveryIter: Array[Int] = {
    val xs = percentToSimulate.map(p => (peopleWhichCanBeTaken.size * p / 100).toInt)
    if (xs.sum != peopleWhichCanBeTaken.size) {
      val leftDueToRounding = peopleWhichCanBeTaken.size - xs.sum
      logger.info(s"leftDueToRounding: $leftDueToRounding")
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

  val rnd: Random = new Random(javaRnd)
  val shouldLogWhenLinksAreNotTheSame: Boolean = false

  val workerParams: R5Parameters = R5Parameters(
    beamConfig = beamConfig,
    transportNetwork = beamServices.beamScenario.transportNetwork,
    vehicleTypes = beamServices.beamScenario.vehicleTypes,
    fuelTypePrices = beamServices.beamScenario.fuelTypePrices,
    ptFares = beamServices.beamScenario.ptFares,
    geo = beamServices.geo,
    dates = beamServices.beamScenario.dates,
    networkHelper = beamServices.networkHelper,
    fareCalculator = beamServices.fareCalculator,
    tollCalculator = beamServices.tollCalculator
  )

  def run(travelTime: TravelTime): TravelTime = {
    val carTravelTimeWriter: CsvWriter = {
      val fileName = controllerIO.getIterationFilename(agentSimIterationNumber, "MultiJDEQSim_car_travel_time.csv")
      new CsvWriter(fileName, Array("iteration", "avg", "median", "p75", "p95", "p99", "min", "max"))
    }
    val reroutedTravelTimeWriter: CsvWriter = {
      val fileName =
        controllerIO.getIterationFilename(agentSimIterationNumber, "MultiJDEQSim_rerouted_car_travel_time.csv")
      new CsvWriter(fileName, Array("iteration", "avg", "median", "p75", "p95", "p99", "min", "max"))
    }
    try {
      logger.info(s"Running ApproxPhysSim")
      run(
        1,
        numberOfPeopleToSimulateEveryIter.length,
        numberOfPeopleToSimulateEveryIter.head,
        SimulationResult(-1, travelTime, Seq.empty, Statistics(Seq.empty)),
        SimulationResult(-1, travelTime, Seq.empty, Statistics(Seq.empty)),
        carTravelTimeWriter,
        reroutedTravelTimeWriter
      ).travelTime
    } finally {
      Try(carTravelTimeWriter.close())
      Try(reroutedTravelTimeWriter.close())
    }
  }

  @tailrec
  final def run(
    currentIter: Int,
    nIterations: Int,
    numberOfPeopleToTake: Int,
    firstResult: SimulationResult,
    lastResult: SimulationResult,
    carTravelTimeWriter: CsvWriter,
    reroutedTravelTimeWriter: CsvWriter,
  ): SimulationResult = {
    if (currentIter > nIterations) {
      logger.info("Last iteration compared with first")
      printStats(firstResult, lastResult)
      lastResult
    } else {
      logger.info(s"finalPopulation size before: ${finalPopulation.getPersons.size}")
      // Get next set of people
      val nextSetOfPeople = {
        val setOfPeople = getNextSetOfPeople(peopleWhichCanBeTaken, rnd, numberOfPeopleToTake)
        logger.info(s"setOfPeople size is ${setOfPeople.size}. numberOfPeopleToTake: $numberOfPeopleToTake")
        // Remove them from original set
        setOfPeople.foreach(peopleWhichCanBeTaken.remove)
        val asCopy = setOfPeople.map { person =>
          createCopyOfPerson(person, finalPopulation.getFactory)
        }
        asCopy.foreach(finalPopulation.addPerson)
        asCopy
      }
      logger.info(
        s"finalPopulation size after: ${finalPopulation.getPersons.size}. Original population size: ${population.getPersons.size}"
      )

      val jdeqSimScenario = initScenario
      val jdeqSimRunner = new JDEQSimRunner(
        beamConfig,
        jdeqSimScenario,
        finalPopulation,
        beamServices,
        controllerIO,
        isCACCVehicle,
        beamConfigChangesObservable,
        agentSimIterationNumber
      )
      val simulationResult =
        jdeqSimRunner.simulate(currentIter, writeEvents = shouldWritePhysSimEvents && currentIter == nIterations)
      carTravelTimeWriter.writeRow(
        Vector(
          currentIter,
          simulationResult.carTravelTimeStats.avg,
          simulationResult.carTravelTimeStats.median,
          simulationResult.carTravelTimeStats.p75,
          simulationResult.carTravelTimeStats.p95,
          simulationResult.carTravelTimeStats.p99,
          simulationResult.carTravelTimeStats.minValue,
          simulationResult.carTravelTimeStats.maxValue
        )
      )
      carTravelTimeWriter.flush()
      val rerouter = new ReRouter(workerParams, beamServices)
      val before = rerouter.printRouteStats(s"Before rerouting at $currentIter iter", finalPopulation)
      //        logger.info("AverageCarTravelTime before replanning")
      //        PhysSim.printAverageCarTravelTime(getCarPeople(population))

      val reroutedTravelTimeStats = rerouter.reroutePeople(simulationResult.travelTime, nextSetOfPeople.toVector)
      reroutedTravelTimeWriter.writeRow(
        Vector(
          currentIter,
          reroutedTravelTimeStats.avg,
          reroutedTravelTimeStats.median,
          reroutedTravelTimeStats.p75,
          reroutedTravelTimeStats.p95,
          reroutedTravelTimeStats.p99,
          reroutedTravelTimeStats.minValue,
          reroutedTravelTimeStats.maxValue
        )
      )
      reroutedTravelTimeWriter.flush()
      //        logger.info("AverageCarTravelTime after replanning")
      //        PhysSim.printAverageCarTravelTime(getCarPeople(population))
      val after = rerouter.printRouteStats(s"After rerouting at $currentIter iter", finalPopulation)
      val absTotalLenDiff = Math.abs(before.totalRouteLen - after.totalRouteLen)
      val absAvgLenDiff = Math.abs(before.totalRouteLen / before.nRoutes - after.totalRouteLen / after.nRoutes)
      val absTotalCountDiff = Math.abs(before.totalLinkCount - after.totalLinkCount)
      val absAvgCountDiff = Math.abs(before.totalLinkCount / before.nRoutes - after.totalLinkCount / after.nRoutes)
      logger.info(s"""
           |Abs diff in total len: $absTotalLenDiff
           |Abs avg diff in len: $absAvgLenDiff
           |Abs dif in total link count: $absTotalCountDiff
           |Abs avg diff in link count: $absAvgCountDiff""".stripMargin)
      printStats(lastResult, simulationResult)
      val realFirstResult = if (currentIter == 1) simulationResult else firstResult
      val nextNumberOfPeopleToTake = numberOfPeopleToSimulateEveryIter.lift(currentIter).getOrElse(-1)
      run(
        currentIter + 1,
        nIterations,
        nextNumberOfPeopleToTake,
        realFirstResult,
        simulationResult,
        carTravelTimeWriter,
        reroutedTravelTimeWriter
      )
    }
  }

  private def printStats(prevResult: SimulationResult, currentResult: SimulationResult): Unit = {
    logger.info(
      s"eventTypeToNumberOfMessages at iteration ${prevResult.iteration}: \n${prevResult.eventTypeToNumberOfMessages.mkString("\n")}"
    )
    logger.info(
      s"eventTypeToNumberOfMessages at iteration ${currentResult.iteration}: \n${currentResult.eventTypeToNumberOfMessages
        .mkString("\n")}"
    )
    val diff =
      (currentResult.eventTypeToNumberOfMessages.map(_._1) ++ prevResult.eventTypeToNumberOfMessages.map(_._1)).toSet
    val diffMap = diff
      .foldLeft(Map.empty[String, Long]) {
        case (acc, key) =>
          val currVal = currentResult.eventTypeToNumberOfMessages.toMap.getOrElse(key, 0L)
          val prevVal = prevResult.eventTypeToNumberOfMessages.toMap.getOrElse(key, 0L)
          val absDiff = Math.abs(currVal - prevVal)
          acc + (key -> absDiff)
      }
      .toList
      .sortBy { case (k, _) => k }
    logger.info(s"Diff in eventTypeToNumberOfMessages map: \n${diffMap.mkString("\n")}")
    //    PhysSim.printAverageCarTravelTime(getCarPeople)
    logger.info(s"Car travel time stats at iteration ${prevResult.iteration}: ${prevResult.carTravelTimeStats}")
    logger.info(s"Car travel time stats at iteration ${currentResult.iteration}: ${currentResult.carTravelTimeStats}")
  }

  private def getNextSetOfPeople(
    xs: collection.Set[Person],
    rnd: Random,
    numberOfPeopleToTake: Int
  ): collection.Set[Person] = {
    val takeN = if (numberOfPeopleToTake > xs.size) xs.size else numberOfPeopleToTake
    val next = rnd.shuffle(xs).take(takeN)
    next
  }

  private def initScenario: Scenario = {
    val jdeqSimScenario = ScenarioUtils.createScenario(agentSimScenario.getConfig).asInstanceOf[MutableScenario]
    jdeqSimScenario.setNetwork(agentSimScenario.getNetwork)
    jdeqSimScenario.setPopulation(finalPopulation)
    val endTimeInSeconds = Time.parseTime(beamConfig.beam.agentsim.endTime).toInt
    jdeqSimScenario.getConfig.travelTimeCalculator().setMaxTime(endTimeInSeconds)
    jdeqSimScenario
  }

  def getBuses(population: Population): Iterable[Person] = {
    population.getPersons.values().asScala.filter(p => p.getId.toString.contains(":"))
  }

  def initPopulation(population: Population): Population = {
    val buses = getBuses(population)
    val newPop = PopulationUtils.createPopulation(agentSimScenario.getConfig)
    buses.foreach { bus =>
      val person: Person = createCopyOfPerson(bus, newPop.getFactory)
      newPop.addPerson(person)
    }
    newPop
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
}
