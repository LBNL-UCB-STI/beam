package beam.metasim.playground.sid.sim

import java.io.File

import beam.metasim.playground.sid.utils.FileUtils
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.controler.Controler
import org.matsim.core.scenario.ScenarioUtils


/**
  * Created by sfeygin on 1/30/17.
  */
object MatSimRunFromScala extends App{

  val ConfigRelPath =  "test/input/beam/actors/"
  val ConfigFileName = "config.xml"
  val SimName = "actors"
  val OutputDirectoryBase = "test/output/beam/basicTests/actors"

  val config:Config = ConfigUtils.loadConfig(ConfigRelPath+ConfigFileName)
  FileUtils.setConfigOutputFile(OutputDirectoryBase,SimName,config)
  val scenario:Scenario = ScenarioUtils.loadScenario(config)

  val controler:Controler = new Controler(scenario)

  controler.run()

}


