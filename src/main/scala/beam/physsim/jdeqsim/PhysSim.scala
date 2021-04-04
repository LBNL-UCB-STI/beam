package beam.physsim.jdeqsim

import beam.router.r5.R5Parameters
import beam.sim.config.BeamConfig
import beam.sim.{BeamConfigChangesObservable, BeamServices}
import beam.utils.Statistics
import beam.utils.csv.CsvWriter
import com.typesafe.scalalogging.{LazyLogging, StrictLogging}
import org.matsim.api.core.v01.Scenario
import org.matsim.api.core.v01.population.{Leg, Person, Population}
import org.matsim.core.controler.OutputDirectoryHierarchy
import org.matsim.core.router.util.TravelTime
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.matsim.core.utils.misc.Time

import scala.annotation.tailrec
import scala.collection.JavaConverters._
import scala.util.{Random, Try}

class PhysSim(
  beamConfig: BeamConfig,
  agentSimScenario: Scenario,
  population: Population,
  beamServices: BeamServices,
  controllerIO: OutputDirectoryHierarchy,
  isCACCVehicle: java.util.Map[String, java.lang.Boolean],
  beamConfigChangesObservable: BeamConfigChangesObservable,
  agentSimIterationNumber: Int,
  shouldWritePhysSimEvents: Boolean,
  javaRnd: java.util.Random
) extends StrictLogging {

  val rnd: Random = new Random(javaRnd)

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

  def run(nIterations: Int, reroutePerIterPct: Double, travelTime: TravelTime): SimulationResult = {
    assert(nIterations >= 1)
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
      logger.info(s"Running PhysSim with nIterations = $nIterations and reroutePerIterPct = $reroutePerIterPct")
      run(
        1,
        nIterations,
        reroutePerIterPct,
        SimulationResult(-1, travelTime, None, Seq.empty, Statistics(Seq.empty)),
        SimulationResult(-1, travelTime, None, Seq.empty, Statistics(Seq.empty)),
        carTravelTimeWriter,
        reroutedTravelTimeWriter
      )
    } finally {
      Try(carTravelTimeWriter.close())
      Try(reroutedTravelTimeWriter.close())
    }
  }

  @tailrec
  final def run(
    currentIter: Int,
    nIterations: Int,
    reroutePerIterPct: Double,
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
      val jdeqSimScenario = initScenario
      val jdeqSimRunner = new JDEQSimRunner(
        beamConfig,
        jdeqSimScenario,
        population,
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
      if (reroutePerIterPct > 0) {
        val rerouter = new ReRouter(workerParams, beamServices)
        val before = rerouter.printRouteStats(s"Before rerouting at $currentIter iter", population)
//        logger.info("AverageCarTravelTime before replanning")
//        PhysSim.printAverageCarTravelTime(getCarPeople(population))
        val reroutedTravelTimeStats = reroute(simulationResult.travelTime, reroutePerIterPct, rerouter)
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
        val after = rerouter.printRouteStats(s"After rerouting at $currentIter iter", population)
        val absTotalLenDiff = Math.abs(before.totalRouteLen - after.totalRouteLen)
        val absAvgLenDiff = Math.abs(before.totalRouteLen / before.nRoutes - after.totalRouteLen / after.nRoutes)
        val absTotalCountDiff = Math.abs(before.totalLinkCount - after.totalLinkCount)
        val absAvgCountDiff = Math.abs(before.totalLinkCount / before.nRoutes - after.totalLinkCount / after.nRoutes)
        logger.info(s"""
             |Abs diff in total len: $absTotalLenDiff
             |Abs avg diff in len: $absAvgLenDiff
             |Abs dif in total link count: $absTotalCountDiff
             |Abs avg diff in link count: $absAvgCountDiff""".stripMargin)
      }
      printStats(lastResult, simulationResult)
      val realFirstResult = if (currentIter == 1) simulationResult else firstResult
      run(
        currentIter + 1,
        nIterations,
        reroutePerIterPct,
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

  private def getCarPeople(population: Population): Vector[Person] = {
    val carPeople = population.getPersons.values.asScala
      .filter(p => !p.getId.toString.contains("bus") && !p.getId.toString.contains(":"))
      .toVector
      .sortBy(x => x.getId.toString)
    carPeople
  }

  private def reroute(travelTime: TravelTime, reroutePerIterPct: Double, rerouter: ReRouter): Statistics = {
    val rightPeopleToReplan = getCarPeople(population)
    val pctToNumberPersonToTake = (rightPeopleToReplan.size * reroutePerIterPct).toInt
    val takeN =
      if (pctToNumberPersonToTake > rightPeopleToReplan.size) rightPeopleToReplan.size else pctToNumberPersonToTake
    if (takeN > 0) {
      val toReroute = rnd.shuffle(rightPeopleToReplan).take(takeN)
      rerouter.reroutePeople(travelTime, toReroute)
    } else
      Statistics(Array.empty[Double])
  }

  private def initScenario = {
    val jdeqSimScenario = ScenarioUtils.createScenario(agentSimScenario.getConfig).asInstanceOf[MutableScenario]
    jdeqSimScenario.setNetwork(agentSimScenario.getNetwork)
    jdeqSimScenario.setPopulation(population)
    val endTimeInSeconds = Time.parseTime(beamConfig.beam.agentsim.endTime).toInt
    jdeqSimScenario.getConfig.travelTimeCalculator().setMaxTime(endTimeInSeconds)
    jdeqSimScenario
  }
}

object PhysSim extends LazyLogging {

  def printAverageCarTravelTime(people: Seq[Person]): Unit = {
    val timeToTravelTime = people.flatMap { person =>
      person.getSelectedPlan.getPlanElements.asScala.collect {
        case leg: Leg =>
          val travelTime = leg.getAttributes.getAttribute("travel_time").toString.toDouble.toInt
          travelTime
      }
    }
    logger.info(s"Some others stats about travel time: ${Statistics(timeToTravelTime.map(_.toDouble))}")
  }
}
