package beam.metasim.sim

import beam.metasim.config.ConfigModule
import beam.metasim.sim.modules.{BeamAgentModule, MetasimModule}
import beam.metasim.utils.FileUtils
import org.matsim.api.core.v01.Scenario
import org.matsim.core.config.{Config, ConfigUtils}
import org.matsim.core.controler._
import org.matsim.core.controler.corelisteners._
import org.matsim.core.mobsim.qsim.QSim
import org.matsim.core.scenario.{ScenarioByConfigModule, ScenarioUtils}

import scala.collection.JavaConversions._
import scala.collection.mutable.ListBuffer


/**
  * Created by sfeygin on 1/30/17.
  */
object MatsimRunFromScala extends App{
  import beam.metasim._
  import net.codingwell.scalaguice.InjectorExtensions._

  // Inject and use tsConfig instead here
  val matsimConfig:Config = ConfigUtils.loadConfig(MatSimConfigLoc+MatSimConfigFilename)
  FileUtils.setConfigOutputFile(OutputDirectoryBase,SimName,matsimConfig)
  val scenario:Scenario = ScenarioUtils.loadScenario(matsimConfig)
  val injector: com.google.inject.Injector =
    Injector.createInjector(matsimConfig, AbstractModule.`override`(ListBuffer(new AbstractModule() {
      override def install(): Unit = {
        
        // MATSim defaults
        val routeConfigGroup = getConfig.plansCalcRoute
        install(new NewControlerModule)
        install(new ScenarioByConfigModule)
        install(new ControlerDefaultsModule)
        install(new ControlerDefaultCoreListenersModule)

        // Beam Inject below:
        install(new ConfigModule)
        install(new MetasimModule)
        install(new BeamAgentModule)
      }
    }),new AbstractModule() {
      override def install(): Unit = {

        // Beam -> MATSim Wirings

        bindMobsim().to(classOf[QSim]) //TODO: This will change
        addControlerListenerBinding().to(classOf[Metasim])
        bind(classOf[ControlerI]).to(classOf[ControlerImpl]).asEagerSingleton()
      }
    }))

  val controler = injector.instance[ControlerI]

  controler.run()


}


