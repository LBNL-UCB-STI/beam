package beam.router.skim

import java.io.File

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.{PathTraversalEvent, SpaceTime}
import beam.agentsim.infrastructure.taz.TAZ
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.TAZSkimmer.{TAZSkimmerInternal, TAZSkimmerKey}
import beam.router.skim.ODSkimmer.{ODSkimmerInternal, ODSkimmerKey}
import beam.router.skim.Skims.SkimType
import beam.router.skim.DriveTimeSkimmer.{DriveTimeSkimmerInternal, DriveTimeSkimmerKey}
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

  "Skimmers" must "results at skims being collected written on disk" in {
    val config = ConfigFactory
      .parseString("""
         |beam.outputs.events.fileOutputFormats = xml
         |beam.physsim.skipPhysSim = true
         |beam.agentsim.lastIteration = 1
         |beam.outputs.writeSkimsInterval = 1
         |beam.h3.resolution = 10
         |beam.h3.lowerBoundResolution = 10
         |beam.router.skim = {
         |  keepKLatestSkims = 1
         |  writeSkimsInterval = 1
         |  writeAggregatedSkimsInterval = 1
         |  travel-time-skimmer {
         |    name = "travel-time-skimmer"
         |    fileBaseName = "skimsTravelTimeObservedVsSimulated"
         |  }
         |  origin_destination_skimmer {
         |    name = "od-skimmer"
         |    fileBaseName = "skimsOD"
         |    writeAllModeSkimsForPeakNonPeakPeriodsInterval = 0
         |    writeFullSkimsInterval = 0
         |  }
         |  taz-skimmer {
         |    name = "taz-skimmer"
         |    fileBaseName = "skimsTAZ"
         |  }
         |}
         |beam.agentsim.agents.modalBehaviors.maximumNumberOfReplanningAttempts = 99999
      """.stripMargin)
      .withFallback(testConfig("test/input/beamville/beam.conf"))
      .resolve()
    runScenarioWithSkimmer(config, classOf[CountSkimmerTester], classOf[CountSkimmerTester])

    // TAZ_SKIMMER
    val countSkimsFromDisk = readSkim(SkimType.TAZ_SKIMMER)
    val countSkimsFromMem = skimsMap(SkimType.TAZ_SKIMMER)
    assume(
      countSkimsFromDisk.size == countSkimsFromMem.size,
      s"${SkimType.TAZ_SKIMMER}: the written skim has a different size from memory"
    )
    countSkimsFromMem.foreach {
      case (key, value) =>
        assume(
          value == countSkimsFromDisk(key),
          s"${SkimType.TAZ_SKIMMER}: the written skim is different from memory"
        )
    }

    // TAZ_SKIMMER
    val ttSkimsFromDisk = readSkim(SkimType.DT_SKIMMER)
    val ttSkimsFromMem = skimsMap(SkimType.DT_SKIMMER)
    assume(
      ttSkimsFromDisk.size == ttSkimsFromMem.size,
      s"${SkimType.DT_SKIMMER}: the written skim has a different size from memory"
    )
    ttSkimsFromMem.foreach {
      case (key, value) =>
        assume(value == ttSkimsFromDisk(key), s"${SkimType.DT_SKIMMER}: the written skim is different from memory")
    }

    // TAZ_SKIMMER
    val odSkimsFromDisk = readSkim(SkimType.OD_SKIMMER)
    val odSkimsFromMem = skimsMap(SkimType.OD_SKIMMER)
    assume(
      odSkimsFromDisk.size == odSkimsFromMem.size,
      s"${SkimType.OD_SKIMMER}: the written skim has a different size from memory"
    )
    odSkimsFromMem.foreach {
      case (key, value) =>
        assume(value == odSkimsFromDisk(key), s"${SkimType.OD_SKIMMER}: the written skim is different from memory")
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
  import Skims._

  val skimsPath = mutable.Map.empty[SkimType.Value, String]
  val skimsMap = mutable.Map.empty[SkimType.Value, immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal]]

  class CountSkimmerTester @Inject()(beamServices: BeamServices)
      extends BasicEventHandler
      with IterationStartsListener
      with ShutdownListener {

    override def handleEvent(event: Event): Unit = {
      event match {
        case e: PathTraversalEvent if e.mode == BeamMode.CAR =>
          beamServices.matsimServices.getEvents.processEvent(
            TAZSkimmerEvent(
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
      if (event.getIteration == 1) {
        // taz_skimmer
        assume(
          Skims.taz_skimmer.pastSkims.size == 1,
          s"at the second iteration there should be only one ${SkimType.TAZ_SKIMMER} collected"
        )
        Skims.taz_skimmer.aggregatedSkim.foreach {
          case (key, value) =>
            assume(
              value == Skims.taz_skimmer.pastSkims.head(key),
              s"the aggregated skims should be equal to the first collected ${SkimType.TAZ_SKIMMER}"
            )
        }

        // od_skimmer
        assume(
          Skims.od_skimmer.pastSkims.size == 1,
          s"at the second iteration there should be only one ${SkimType.OD_SKIMMER} collected"
        )
        Skims.od_skimmer.aggregatedSkim.foreach {
          case (key, value) =>
            assume(
              value == Skims.od_skimmer.pastSkims.head(key),
              s"the aggregated skims should be equal to the first collected ${SkimType.OD_SKIMMER}"
            )
        }

        // dt_skimmer
        assume(
          Skims.dt_skimmer.pastSkims.size == 1,
          s"at the second iteration there should be only one ${SkimType.DT_SKIMMER} collected"
        )
        Skims.dt_skimmer.aggregatedSkim.foreach {
          case (key, value) =>
            assume(
              value == Skims.dt_skimmer.pastSkims.head(key),
              s"the aggregated skims should be equal to the first collected ${SkimType.DT_SKIMMER}"
            )
        }
      }
    }

    override def notifyShutdown(event: ShutdownEvent): Unit = {
      skimsPath.put(
        SkimType.DT_SKIMMER,
        event.getServices.getControlerIO.getIterationFilename(1, "skimsTravelTimeObservedVsSimulated_Aggregated.csv.gz")
      )
      skimsPath.put(
        SkimType.OD_SKIMMER,
        event.getServices.getControlerIO.getIterationFilename(1, "skimsOD_Aggregated.csv.gz")
      )
      skimsPath.put(
        SkimType.TAZ_SKIMMER,
        event.getServices.getControlerIO.getIterationFilename(1, "skimsTAZ_Aggregated.csv.gz")
      )
      skimsMap.put(SkimType.DT_SKIMMER, Skims.dt_skimmer.aggregatedSkim)
      skimsMap.put(SkimType.OD_SKIMMER, Skims.od_skimmer.aggregatedSkim)
      skimsMap.put(SkimType.TAZ_SKIMMER, Skims.taz_skimmer.aggregatedSkim)
    }
  }

  private def readSkim(skimType: SkimType.Value): immutable.Map[AbstractSkimmerKey, AbstractSkimmerInternal] = {
    var mapReader: CsvMapReader = null
    val res = mutable.Map.empty[AbstractSkimmerKey, AbstractSkimmerInternal]
    try {
      if (new File(skimsPath(skimType)).isFile) {
        mapReader = new CsvMapReader(FileUtils.readerFromFile(skimsPath(skimType)), CsvPreference.STANDARD_PREFERENCE)
        val header = mapReader.getHeader(true)
        var line: java.util.Map[String, String] = mapReader.read(header: _*)
        while (null != line) {
          import scala.collection.JavaConverters._
          val (key, value) = skimType match {
            case SkimType.OD_SKIMMER  => getODSkimPair(line.asScala.toMap)
            case SkimType.TAZ_SKIMMER => getCountSkimPair(line.asScala.toMap)
            case _                    => getTTSkimPair(line.asScala.toMap)
          }
          res.put(key, value)
          line = mapReader.read(header: _*)
        }
      } else {
        logger.info(s"Could not load skim from '${skimsPath(skimType)}'")
      }
    } catch {
      case NonFatal(ex) =>
        logger.error(s"Could not load skim from '${skimsPath(skimType)}': ${ex.getMessage}", ex)
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
        energy = Option(row("energy")).map(_.toDouble).getOrElse(0.0),
        numObservations = row("numObservations").toInt,
        numIteration = row("numIteration").toInt,
      )
    )
  }

  private def getTTSkimPair(row: Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      DriveTimeSkimmerKey(
        fromTAZId = Id.create(row("fromTAZId"), classOf[TAZ]),
        toTAZId = Id.create(row("toTAZId"), classOf[TAZ]),
        hour = row("hour").toInt
      ),
      DriveTimeSkimmerInternal(
        timeSimulated = row("timeSimulated").toDouble,
        timeObserved = row("timeObserved").toDouble,
        numObservations = row("counts").toInt,
        numIteration = row("numIteration").toInt
      )
    )
  }

  private def getCountSkimPair(row: Map[String, String]): (AbstractSkimmerKey, AbstractSkimmerInternal) = {
    (
      TAZSkimmerKey(
        row("time").toInt,
        Id.create(row("taz"), classOf[TAZ]),
        row("hex"),
        row("groupId"),
        row("label")
      ),
      TAZSkimmerInternal(
        row("sumValue").toDouble,
        row("meanValue").toDouble,
        row("numObservations").toInt,
        row("numIteration").toInt
      )
    )
  }
}
