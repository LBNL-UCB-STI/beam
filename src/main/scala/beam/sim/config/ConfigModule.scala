package beam.sim.config

import java.io.File
import java.net.URI
import java.nio.file.{Files, Paths}

import beam.sim.config.ConfigModule.{BeamConfigProvider, ConfigProvider}
import com.google.inject.{AbstractModule, Inject, Provider}
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.config.{Config => MatSimConfig}
import org.slf4j.{Logger, LoggerFactory}

/**
  * Unified factory for building config objects( BEAM, MATSim and low-level typesafe) .
  * It guarantees
  *
  * @author sfeygin
  * @author dserdiuk
  */
object ConfigModule {
  val DefaultConfigFileName = "beam.conf"
  var ConfigFileName: Option[String] = None
  private val logger: Logger = LoggerFactory.getLogger(ConfigModule.getClass)

  lazy val typesafeConfig: TypesafeConfig = {

    val inputDir = sys.env.get("BEAM_SHARED_INPUTS")
    val config = ConfigFileName match {
      case Some(fileName) if Files.exists(Paths.get(fileName)) =>
        ConfigFactory.parseFile(Paths.get(fileName).toFile)
      case Some(fileName) if inputDir.isDefined && Files.exists(Paths.get(inputDir.get, fileName)) =>
        ConfigFactory.parseFile(Paths.get(inputDir.get, fileName).toFile)
      case Some(fileName) if getClass.getClassLoader.getResources(fileName).hasMoreElements =>
        ConfigFactory.parseResources(fileName)
      case _ =>
        ConfigFactory.parseResources(DefaultConfigFileName)
    }
    if (logger.isInfoEnabled()) {
      val location = config.origin().url()
      logger.info(s"Loaded BEAM config from $location ")
    }
    config.resolve()
  }

  protected [beam] lazy val matSimConfig: MatSimConfig = {
    try {
      val configBuilder = new MatSimBeamConfigBuilder(typesafeConfig)
      configBuilder.buildMatSamConf()
    } catch {
      case e: Exception =>
        logger.error(s"Error while building MATSim config from $DefaultConfigFileName", e)
        throw e
    }
  }

 lazy val beamConfig = BeamConfig(typesafeConfig)

  class ConfigProvider extends Provider[TypesafeConfig] {
    override def get(): TypesafeConfig = typesafeConfig
  }

  class BeamConfigProvider extends Provider[BeamConfig] {
    override def get(): BeamConfig = beamConfig
  }

  implicit class ExtendedBeamConfig(beamConfig: BeamConfig) {

    val bbBuffer = 100000
    val MaxPickupTimeInSeconds = 15 * 60
  }
}

class ConfigModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[TypesafeConfig].toProvider[ConfigProvider].asEagerSingleton()
    bind[BeamConfig].toProvider[BeamConfigProvider].asEagerSingleton()
  }
}
