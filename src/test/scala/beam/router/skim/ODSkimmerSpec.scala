package beam.router.skim

import java.io.File

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.sim.config.{BeamConfig, MatSimBeamConfigBuilder}
import beam.sim.population.DefaultPopulationAdjustment
import beam.sim.{BeamHelper, BeamServices}
import beam.utils.FileUtils
import beam.utils.TestConfigUtils.testConfig
import com.google.inject.Inject
import com.typesafe.config.{Config, ConfigFactory}
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.core.controler.AbstractModule
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent, ShutdownEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener, ShutdownListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}
import org.scalatest.{FlatSpec, Matchers}
import org.supercsv.io.CsvMapReader
import org.supercsv.prefs.CsvPreference

import scala.collection.concurrent.TrieMap
import scala.collection.immutable
import scala.collection.mutable
import scala.util.control.NonFatal

class ODSkimmerSpec extends FlatSpec with Matchers with BeamHelper {
  import ODSkimmerSpec._

  // REPOSITION
  "OD Skimmer Scenario" must "results at skims being written" in {
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
         |        name = "origin-destination-skimmer"
         |        skimType = "od-skimmer"
         |        skimFileBaseName = "skimsOD"
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
    runScenarioWithODSkimmer(config)
  }

  private def runScenarioWithODSkimmer(config: Config): Unit = {
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
          addEventHandlerBinding().to(classOf[ODSkimmerTester])
          addControlerListenerBinding().to(classOf[ODSkimmerTester])
        }
      }
    )
    val services = injector.getInstance(classOf[BeamServices])
    DefaultPopulationAdjustment(services).update(scenario)
    services.controler.run()

    assume(avgSkimIt0 == skimIt0, "ODSkim is not collected properly")
    assume(avgSkimAVG == skimAVG, "ODSkim is not collected properly")
    //assume(skimIteration1, "the overall average of the ODSkim throughout iteration 0 and 1 is not correct")
  }
}

object ODSkimmerSpec extends LazyLogging {

  var avgSkimIt0: ODSkimmerInternal = _
  var avgSkimAVG: ODSkimmerInternal = _
  var skimIt0: ODSkimmerInternal = _
  var skimAVG: ODSkimmerInternal = _

  var avgSkimCur: ODSkimmerInternal = ODSkimmerInternal(0, 0, 0, 0, 0, 0, 0)

  class ODSkimmerTester @Inject()(beamServices: BeamServices)
      extends BasicEventHandler
      with IterationEndsListener
      with ShutdownListener {

    override def handleEvent(event: Event): Unit = {
      event match {
        case e: PathTraversalEvent if e.mode == BeamMode.CAR =>
          val energy = (e.endLegPrimaryFuelLevel + e.endLegSecondaryFuelLevel) - (e.primaryFuelConsumed + e.secondaryFuelConsumed)
          val tt = e.arrivalTime - e.departureTime
          val event = ODSkimmerEvent(e.time, beamServices, getEmbodiedTrip(e), tt * 1.2, e.amountPaid * 1.2, energy)
          val count = avgSkimCur.numObservations + 1
          beamServices.matsimServices.getEvents.processEvent(
            ODSkimmerEvent(e.time, beamServices, getEmbodiedTrip(e), tt * 1.2, e.amountPaid * 1.2, energy)
          )
          avgSkimCur = ODSkimmerInternal(
            travelTimeInS = (avgSkimCur.numObservations * avgSkimCur.travelTimeInS + tt) / count,
            generalizedTimeInS = (avgSkimCur.numObservations * avgSkimCur.generalizedTimeInS + event.generalizedTimeInHours) / count,
            generalizedCost = (avgSkimCur.numObservations * avgSkimCur.generalizedCost + event.generalizedCost) / count,
            distanceInM = (avgSkimCur.numObservations * avgSkimCur.distanceInM + e.legLength) / count,
            cost = (avgSkimCur.numObservations * avgSkimCur.cost + e.amountPaid) / count,
            numObservations = count,
            energy = (avgSkimCur.numObservations * avgSkimCur.energy + event.energyConsumption) / count
          )
        case _ =>
      }
    }

    override def notifyIterationEnds(event: IterationEndsEvent): Unit = {
      if (event.getIteration == 0) {
        avgSkimIt0 = avgSkimCur
        avgSkimCur = ODSkimmerInternal(0, 0, 0, 0, 0, 0, 0)
      } else {
        val count = avgSkimIt0.numObservations + avgSkimCur.numObservations
        avgSkimAVG = ODSkimmerInternal(
          travelTimeInS = (avgSkimIt0.numObservations * avgSkimIt0.travelTimeInS + avgSkimCur.numObservations * avgSkimCur.travelTimeInS) / count,
          generalizedTimeInS = (avgSkimIt0.numObservations * avgSkimIt0.generalizedTimeInS + avgSkimCur.numObservations * avgSkimCur.generalizedTimeInS) / count,
          generalizedCost = (avgSkimIt0.numObservations * avgSkimIt0.generalizedCost + avgSkimCur.numObservations * avgSkimCur.generalizedCost) / count,
          distanceInM = (avgSkimIt0.numObservations * avgSkimIt0.distanceInM + avgSkimCur.numObservations * avgSkimCur.distanceInM) / count,
          cost = (avgSkimIt0.numObservations * avgSkimIt0.cost + avgSkimCur.numObservations * avgSkimCur.cost) / count,
          numObservations = count,
          energy = (avgSkimIt0.numObservations * avgSkimIt0.energy + avgSkimCur.numObservations * avgSkimCur.energy) / count
        )
      }
    }
    override def notifyShutdown(event: ShutdownEvent): Unit = {
      skimIt0 = getAvgODSkim(event.getServices.getControlerIO.getIterationFilename(0, "skimsOD.csv.gz"))
      skimAVG = getAvgODSkim(event.getServices.getControlerIO.getIterationFilename(1, "skimsODAggregated.csv.gz"))
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

  private def getAvgODSkim(filePath: String) = {
    val skimMap = readSkim(filePath)
    var avgODSkim: ODSkimmerInternal = ODSkimmerInternal(0, 0, 0, 0, 0, 0, 0)
    skimMap
      .filter(_._1.isInstanceOf[ODSkimmerKey])
      .filter(_._1.asInstanceOf[ODSkimmerKey].mode == BeamMode.CAR)
      .values
      .foreach {
        case value: ODSkimmerInternal =>
          val count = value.numObservations + avgODSkim.numObservations
          avgODSkim = ODSkimmerInternal(
            travelTimeInS = (value.numObservations * value.travelTimeInS + avgODSkim.numObservations * avgODSkim.travelTimeInS) / count,
            generalizedTimeInS = (value.numObservations * value.generalizedTimeInS + avgODSkim.numObservations * avgODSkim.generalizedTimeInS) / count,
            generalizedCost = (value.numObservations * value.generalizedCost + avgODSkim.numObservations * avgODSkim.generalizedCost) / count,
            distanceInM = (value.numObservations * value.distanceInM + avgODSkim.numObservations * avgODSkim.distanceInM) / count,
            cost = (value.numObservations * value.cost + avgODSkim.numObservations * avgODSkim.cost) / count,
            numObservations = count,
            energy = (value.numObservations * value.energy + avgODSkim.numObservations * avgODSkim.energy) / count
          )
        case _ =>
      }
    avgODSkim
  }

  private def readSkim(filePath: String): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    var mapReader: CsvMapReader = null
    val res = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    try {
      if (new File(filePath).isFile) {
        mapReader = new CsvMapReader(FileUtils.readerFromFile(filePath), CsvPreference.STANDARD_PREFERENCE)
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          import scala.collection.JavaConverters._
          val row = line.asScala.toMap
          res.put(
            ODSkimmerKey(
              row("hour").toInt,
              BeamMode.fromString(row("mode").toLowerCase()).get,
              Id.create(row("origTaz"), classOf[TAZ]),
              Id.create(row("destTaz"), classOf[TAZ])
            ),
            ODSkimmerInternal(
              row("travelTimeInS").toDouble,
              row("generalizedTimeInS").toDouble,
              row("generalizedCost").toDouble,
              row("distanceInM").toDouble,
              row("cost").toDouble,
              row("numObservations").toInt,
              Option(row("energy")).map(_.toDouble).getOrElse(0.0)
            )
          )
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

}
