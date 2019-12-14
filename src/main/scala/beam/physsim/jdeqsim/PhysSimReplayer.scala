package beam.physsim.jdeqsim

import java.io.{Closeable, File}
import java.nio.charset.StandardCharsets
import java.nio.file.Files

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamHelper
import beam.sim.config.BeamConfig
import beam.utils.{BeamConfigUtils, EventReader}
import com.typesafe.config.{Config, ConfigFactory, ConfigResolveOptions, ConfigValueFactory}
import org.matsim.api.core.v01.events.Event
import org.matsim.core.scenario.{MutableScenario, ScenarioUtils}

import scala.io.Source
import scala.util.Try

class PhysSimReplayer {
}

object PhysSimReplayer {
  def eventsFilter(event: Event): Boolean = {
    val attribs = event.getAttributes
    // We need only PathTraversal
    val isNeededEvent = event.getEventType == "PathTraversal"
    isNeededEvent
  }

  def main(args: Array[String]): Unit = {
    assert(args.length == 3, "Expected two args: first arg is the to beam.conf, second args is the path to warmstart zip archive, thirt arg is the path to events file")
    val pathToBeamConfig: String = args(0)
    val rootFile = new File(pathToBeamConfig).getParentFile
    assert(rootFile.exists(), s"Folder $rootFile should exist")

    // This is lazy, it just creates an iterator
    val (ptes: Iterator[PathTraversalEvent], closable: Closeable) = {
      val (e, c) = EventReader.fromCsvFile(args(2), eventsFilter)
      (e.map(PathTraversalEvent.apply), c)
    }

    try {
      val beamTypesafeConfig = readBeamConfig(pathToBeamConfig, args(1))

      val beamHelper = new BeamHelper {}
      val beamSvc = beamHelper.prepareBeamService(beamTypesafeConfig)

      ptes.foreach { pte =>
        println(pte)
      }
    }
    finally {
      Try(closable.close())
    }
  }

//  def apply(pathEvents: String, pathPopulation: String): PhysSimReplayer = {
//
//    val beamHelper = new BeamHelper {}
//    val beamScenario = beamHelper.loadScenario(beamConfig)
//    val scenario = {
//      val result = ScenarioUtils.loadScenario(matsimConfig).asInstanceOf[MutableScenario]
//      fixDanglingPersons(result)
//      result
//    }
//    (scenario, beamScenario)
//
//
//    new PhysSimReplayer(pathEvents)
//  }
  private def readBeamConfig(pathToBeamConfig: String, pathToWarmStartZip: String): Config = {
    val rootFile = new File(pathToBeamConfig).getParentFile
    val src = Source.fromFile(new File(pathToBeamConfig))
    val fixedBeamCfg = try {
      val str = src.mkString
        .replace("""include "../../common/akka.conf"""", """include "akka.conf"""")
        .replace("""include "../../common/metrics.conf"""", """include "metrics.conf"""")
        .replace("""include "../../common/matsim.conf"""", """include "matsim.conf"""")
      val tempPath = Files.createTempFile(rootFile.toPath, "beam_to_be_loaded", "conf")
      // tempPath.toFile.deleteOnExit()
      Files.write(tempPath, str.getBytes(StandardCharsets.UTF_8))
      val pwd = System.getenv("PWD")
      val overrideParams = ConfigFactory.empty()
        .withValue("beam.agentsim.agents.vehicles.vehicleTypesFilePath",  ConfigValueFactory.fromAnyRef(s"""$pwd/vehicletypes-baseline.csv"""))
        .withValue("beam.agentsim.agents.vehicles.fuelTypesFilePath",  ConfigValueFactory.fromAnyRef(s"""$pwd/fuelTypes.csv"""))
        .withValue("beam.physsim.inputNetworkFilePath",  ConfigValueFactory.fromAnyRef(s"""$pwd/r5-simple-no-local/physsim-network.xml"""))
        .withValue("beam.routing.r5.directory",  ConfigValueFactory.fromAnyRef(s"""$pwd/r5-simple-no-local"""))
        .withValue("beam.routing.r5.osmFile",  ConfigValueFactory.fromAnyRef(s"""$pwd/r5-simple-no-local/sf-bay.osm.pbf"""))
        .withValue("beam.routing.r5.osmMapdbFile",  ConfigValueFactory.fromAnyRef(s"""$pwd/r5-simple-no-local/osm.mapdb"""))
        .withValue("beam.exchange.scenario.source",  ConfigValueFactory.fromAnyRef(s"""Beam"""))
        .withValue("beam.warmStart.enabled",  ConfigValueFactory.fromAnyRef(true))
        .withValue("beam.warmStart.path",  ConfigValueFactory.fromAnyRef(pathToWarmStartZip))
        .withValue("beam.agentsim.taz.filePath",  ConfigValueFactory.fromAnyRef(s"""$pwd/taz-centers.csv"""))
        .withValue("beam.calibration.roadNetwork.travelTimes.zoneBoundariesFilePath",  ConfigValueFactory.fromAnyRef(s"""$pwd/calibration/san_francisco_censustracts.json"""))
        .withValue("beam.calibration.roadNetwork.travelTimes.zoneODTravelTimesFilePath",  ConfigValueFactory.fromAnyRef(s"""$pwd/calibration/san_francisco-censustracts-2018-3-OnlyWeekdays-HourlyAggregate.csv.gz"""))
      val cfg = overrideParams
        .withFallback(BeamConfigUtils.parseFileSubstitutingInputDirectory(tempPath.toFile))
        .resolve(ConfigResolveOptions.defaults()) // (ConfigResolveOptions.defaults().setAllowUnresolved(true))
      cfg
    }
    finally {
      Try(src.close())
    }
    fixedBeamCfg
  }
}
