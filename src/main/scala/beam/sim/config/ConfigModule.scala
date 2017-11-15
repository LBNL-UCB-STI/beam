package beam.sim.config

import java.nio.file.{Files, Paths}

import beam.agentsim.agents.vehicles.Trajectory
import beam.sim.common.GeoUtils
import com.google.inject._
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.config.{Config => MatSimConfig}

class ConfigModule(val typesafeConfig: TypesafeConfig) extends AbstractModule with ScalaModule {

  @Provides @Singleton
  def getTypesafeConfig: TypesafeConfig = {
    typesafeConfig
  }

  @Provides @Singleton
  def beamConfig(typesafeConfig: TypesafeConfig): BeamConfig = BeamConfig(typesafeConfig)

  implicit class ExtendedBeamConfig(beamConfig: BeamConfig) {

    val bbBuffer = 100000
    val MaxPickupTimeInSeconds = 15 * 60
  }

  override def configure(): Unit = {
    requestInjection(GeoUtils)
    requestInjection(Trajectory)
  }

}

object ConfigModule {
  val DefaultConfigFileName = "beam.conf"

  def matSimConfig(config: TypesafeConfig): MatSimConfig = {
    val configBuilder = new MatSimBeamConfigBuilder(config)
    configBuilder.buildMatSamConf()
  }

  def loadConfig(filename: Option[String]): TypesafeConfig = {
    val inputDir = sys.env.get("BEAM_SHARED_INPUTS")
    val config = filename match {
      case Some(fileName) if Files.exists(Paths.get(fileName)) =>
        ConfigFactory.parseFile(Paths.get(fileName).toFile)
      case Some(fileName) if inputDir.isDefined && Files.exists(Paths.get(inputDir.get, fileName)) =>
        ConfigFactory.parseFile(Paths.get(inputDir.get, fileName).toFile)
      case Some(fileName) if getClass.getClassLoader.getResources(fileName).hasMoreElements =>
        ConfigFactory.parseResources(fileName)
      case _ =>
        ConfigFactory.parseResources(DefaultConfigFileName)
    }
    config.resolve()
  }
}
