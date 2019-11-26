package beam.router.skim

import java.io.File

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.CountSkimmer.{CountSkimmerInternal, CountSkimmerKey}
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.google.inject.Inject
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.events.Event
import org.matsim.api.core.v01.{Coord, Id}
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.{IterationStartsEvent, ShutdownEvent}
import org.matsim.core.controler.listener.{IterationStartsListener, ShutdownListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.{immutable, mutable}
import scala.util.control.NonFatal

class SkimmerSpec extends FlatSpec with Matchers with BeamHelper {
  import SkimmerSpec._

  // REPOSITION
  "OD Skimmer Scenario" must "results at skims being written on disk" in {
    val config = ConfigFactory
      .parseString("""
         |beam.outputs.events.fileOutputFormats = xml
         |beam.physsim.skipPhysSim = true
         |beam.agentsim.lastIteration = 1
         |beam.outputs.writeSkimsInterval = 1
         |beam.h3.resolution = 10
         |beam.h3.lowerBoundResolution = 10
         |beam.router.skim = {
         |  keepKLatestSkims = 2
         |  aggregateFunction = "AVG"
         |  writeSkimsInterval = 1
         |  writeAggregatedSkimsInterval = 1
         |  skimmers = [
         |    {
         |      od-skimmer {
         |        name = "origin-destination-skimmer-test"
         |        skimType = "od-skimmer-test"
         |        skimFileBaseName = "skimsODTest"
         |        writeAllModeSkimsForPeakNonPeakPeriodsInterval = 0
         |        writeFullSkimsInterval = 0
         |      }
         |    }
         |  ]
         |}
         |beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 99999
      """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runScenarioWithSkimmer(config, classOf[ODSkimmerTester], classOf[ODSkimmerTester])
    val skimMap = readSkim(SkimmerSpec.avgODSkimFilePath, "od-skimmer-test")
    avgODSkim.foreach {
      case (key, value) =>
        assume(value == skimMap(key), "the skim on disk is different from the on memory")
    }
  }

  // REPOSITION
  "Count Skimmer Scenario" must "results at skims being written on disk" in {
    val config = ConfigFactory
      .parseString("""
         |beam.outputs.events.fileOutputFormats = xml
         |beam.physsim.skipPhysSim = true
         |beam.agentsim.lastIteration = 1
         |beam.outputs.writeSkimsInterval = 1
         |beam.h3.resolution = 10
         |beam.h3.lowerBoundResolution = 10
         |beam.router.skim = {
         |  keepKLatestSkims = 2
         |  aggregateFunction = "AVG"
         |  writeSkimsInterval = 1
         |  writeAggregatedSkimsInterval = 1
         |  skimmers = [
         |    {
         |      count-skimmer {
         |        name = "count-skimmer-test"
         |        skimType = "count-skimmer-test"
         |        skimFileBaseName = "skimsCountTest"
         |      }
         |    }
         |  ]
         |}
         |beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 99999
      """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runScenarioWithSkimmer(config, classOf[CountSkimmerTester], classOf[CountSkimmerTester])
    val skimMap = readSkim(SkimmerSpec.avgCountSkimFilePath, "count-skimmer-test")
    avgCountSkim.foreach {
      case (key, value) =>
        assume(value == skimMap(key), "the skim on disk is different from the on memory")
    }
  }

  private def runScenarioWithSkimmer(
    config: Config,
    eventHandlerClass: Class[_ <: BasicEventHandler],
    listenerClass: Class[_ <: IterationStartsListener]
  ): Unit = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    val matsimConfig = configBuilder.buildMatSimConf()
    val beamConfig = BeamConfig(config)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val beamScenario = loadScenario(beamConfig)
    FileUtils.setConfigOutputFile(beamConfig, matsimConfig)
    val scenario = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
    scenario.setNetwork(beamScenario.network)
    val injector = org.matsim.core.controler.Injector.createInjector(
      scenario.getConfig,
      new AbstractModule() {
        override def install(): Unit = {
          install(module(config, beamConfig, scenario, beamScenario))
          addEventHandlerBinding().to(eventHandlerClass)
          addControlerListenerBinding().to(listenerClass)
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)
    services.controler.run()
  }
}

object SkimmerSpec extends LazyLogging {

  var avgODSkim = immutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
  var avgODSkimFilePath = ""
  var avgCountSkim = immutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
  var avgCountSkimFilePath = ""

  class CountSkimmerTester @Inject()(beamServices: BeamServices)
      extends BasicEventHandler
      with IterationStartsListener
      with ShutdownListener {

    override def handleEvent(event: Event): Unit = {
      event match {
        case e: PathTraversalEvent if e.mode == BeamMode.CAR =>
          beamServices.matsimServices.getEvents.processEvent(
            CountSkimmerEvent(
              event.getTime,
              beamServices,
              (event.getTime / 3600).toInt * 3600,
              new Coord(e.startX, e.startY),
              "default",
              e.mode.toString
            )
          )
        case _ =>
      }
    }

    override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
      assume(Skims.lookup("count-skimmer-test").isDefined, "count-skimmer-test is not defined")
      val countSkims = Skims.lookup("count-skimmer-test").get.asInstanceOf[CountSkims]
      if (event.getIteration == 1) {
        assume(
          countSkims.pastSkims.size == 1,
          "at the second iteration there should be only one count-skimmer-test collected"
        )
        countSkims.aggregatedSkim.foreach {
          case (key, value) =>
            assume(
              value == countSkims.pastSkims.head(key),
              "the aggregated skims should be equal to the first collected count-skimmer-test"
            )
        }
      }
    }

    override def notifyShutdown(event: ShutdownEvent): Unit = {
      val countSkims = Skims.lookup("count-skimmer-test").get.asInstanceOf[CountSkims]
      avgCountSkim = countSkims.aggregatedSkim
      avgCountSkimFilePath = event.getServices.getControlerIO.getIterationFilename(1, "skimsCountTestAggregated.csv.gz")
    }
  }

  class ODSkimmerTester @Inject()(beamServices: BeamServices)
      extends BasicEventHandler
      with IterationStartsListener
      with ShutdownListener {

    override def handleEvent(event: Event): Unit = {
      event match {
        case e: PathTraversalEvent if e.mode == BeamMode.CAR =>
          val energy = (e.endLegPrimaryFuelLevel + e.endLegSecondaryFuelLevel) - (e.primaryFuelConsumed + e.secondaryFuelConsumed)
          val tt = e.arrivalTime - e.departureTime
          beamServices.matsimServices.getEvents.processEvent(
            ODSkimmerEvent(e.time, beamServices, getEmbodiedTrip(e), tt * 1.2, e.amountPaid * 1.2, energy)
          )
        case _ =>
      }
    }

    override def notifyShutdown(event: ShutdownEvent): Unit = {
      val odSkims = Skims.lookup("od-skimmer-test").get.asInstanceOf[ODSkims]
      avgODSkim = odSkims.aggregatedSkim
      avgODSkimFilePath = event.getServices.getControlerIO.getIterationFilename(1, "skimsODTestAggregated.csv.gz")
    }

    override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
      assume(Skims.lookup("od-skimmer-test").isDefined, "od-skimmer-test is not defined")
      val odSkims = Skims.lookup("od-skimmer-test").get.asInstanceOf[ODSkims]
      if (event.getIteration == 1) {
        assume(
          odSkims.pastSkims.size == 1,
          "at the second iteration there should be only one od-skimmer-test collected"
        )
        odSkims.aggregatedSkim.foreach {
          case (key, value) =>
            assume(
              value == odSkims.pastSkims.head(key),
              "the aggregated skims should be equal to the first collected od-skimmer-test"
            )
        }
      }
    }
  }

  private def getEmbodiedTrip(e: PathTraversalEvent): EmbodiedBeamTrip = {
    EmbodiedBeamTrip(
      IndexedSeq(
        EmbodiedBeamLeg(
          beamLeg = BeamLeg(
            startTime = 0,
            mode = BeamMode.WALK,
            duration = 0,
            travelPath = BeamPath(
              linkIds = e.linkIds.headOption match {
                case Some(linkid) => Vector(linkid)
                case _            => Vector()
              },
              linkTravelTime = e.linkTravelTime.headOption match {
                case Some(linktt) => Vector(linktt)
                case _            => Vector()
              },
              transitStops = None,
              startPoint = SpaceTime(e.startX, e.startY, e.departureTime),
              endPoint = SpaceTime(e.startX, e.startY, e.departureTime),
              distanceInM = 0
            )
          ),
          beamVehicleId = Id.createVehicleId("body-dummyAgent"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 0.0,
          unbecomeDriverOnCompletion = true
        ),
        EmbodiedBeamLeg(
          BeamLeg(
            e.departureTime,
            BeamMode.CAR,
            e.arrivalTime - e.departureTime,
            BeamPath(
              e.linkIds,
              e.linkTravelTime,
              None,
              SpaceTime(e.startX, e.startY, e.departureTime),
              SpaceTime(e.endX, e.endY, e.arrivalTime),
              e.legLength
            )
          ),
          Id.createVehicleId(e.vehicleId),
          Id.create(e.vehicleType, classOf[BeamVehicleType]),
          asDriver = true,
          e.amountPaid,
          unbecomeDriverOnCompletion = true
        ),
        EmbodiedBeamLeg(
          beamLeg = BeamLeg(
            startTime = e.arrivalTime,
            mode = BeamMode.WALK,
            duration = 0,
            travelPath = BeamPath(
              linkIds = e.linkIds.lastOption match {
                case Some(linkid) => Vector(linkid)
                case _            => Vector()
              },
              linkTravelTime = e.linkTravelTime.lastOption match {
                case Some(linktt) => Vector(linktt)
                case _            => Vector()
              },
              transitStops = None,
              startPoint = SpaceTime(e.endX, e.endY, e.arrivalTime),
              endPoint = SpaceTime(e.endX, e.endY, e.arrivalTime),
              distanceInM = 0
            )
          ),
          beamVehicleId = Id.createVehicleId("body-dummyAgent"),
          Id.create("BODY-TYPE-DEFAULT", classOf[BeamVehicleType]),
          asDriver = true,
          cost = 0.0,
          unbecomeDriverOnCompletion = true
        )
      )
    )
  }

  private def readSkim(
    filePath: String,
    skimType: String
  ): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    var mapReader: CsvMapReader = null
    val res = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    try {
      if (new File(filePath).isFile) {
        mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          import scala.collection.JavaConverters._
          val (key, value) = if (skimType == "od-skimmer-test") {
            getODSkimPair(line.asScala.toMap)
          } else {
            getCountSkimPair(line.asScala.toMap)
          }
          res.put(key, value)
          line = mapReader.read(header: _*)
        }
      } else {
        logger.info(s"Could not load skim from '${filePath}'")
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not load skim from '${filePath}': ${ex.getMessage}", ex)
    } finally {
      if (null != mapReader)
        mapReader.close()
    }
    res.toMap
  }

  private def getODSkimPair(row: Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      ODSkimmerKey(
        hour = row("hour").toInt,
        mode = BeamMode.fromString(row("mode").toLowerCase()).get,
        originTaz = Id.create(row("origTaz"), classOf[TAZ]),
        destinationTaz = Id.create(row("destTaz"), classOf[TAZ])
      ),
      ODSkimmerInternal(
        travelTimeInS = row("travelTimeInS").toDouble,
        generalizedTimeInS = row("generalizedTimeInS").toDouble,
        generalizedCost = row("generalizedCost").toDouble,
        distanceInM = row("distanceInM").toDouble,
        cost = row("cost").toDouble,
        numObservations = row("numObservations").toInt,
        energy = Option(row("energy")).map(_.toDouble).getOrElse(0.0)
      )
    )
  }

  private def getCountSkimPair(row: Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      CountSkimmerKey(
        row("time").toInt,
        Id.create(row("taz"), classOf[TAZ]),
        row("hex"),
        row("groupId"),
        row("label")
      ),
      CountSkimmerInternal(row("count").toInt)
    )
  }
}
