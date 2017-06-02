package beam.agentsim.config

import beam.agentsim.config.ConfigModule.{BeamConfigProvider, ConfigProvider}
import com.google.inject.{AbstractModule, Inject, Provider}
import com.typesafe.config.{ConfigFactory, Config => TypesafeConfig}
import net.codingwell.scalaguice.ScalaModule
import org.matsim.core.config.{Config => MatSimConfig}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._

/**
  * Unified factory for building config objects( BEAM, MATSim and low-level typesafe) .
  * It guarantees
  *
  * @author sfeygin
  * @author dserdiuk
  */
object ConfigModule {
  val DefaultConfigFileName = "beam.conf"
  private val logger: Logger = LoggerFactory.getLogger(ConfigModule.getClass)

  protected [agentsim] lazy val typesafeConfig: TypesafeConfig = {
    if (logger.isInfoEnabled()) {
      val location = getClass.getClassLoader.getResources(DefaultConfigFileName).asScala.toList.headOption.map(_.toURI.toString)
      logger.info(s"Loading BEAM config from $location ")
    }
    val config = ConfigFactory.parseResources(DefaultConfigFileName).resolve()
    config
  }

  protected [agentsim] lazy val matSimConfig: MatSimConfig = {
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
}

class ConfigModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[TypesafeConfig].toProvider[ConfigProvider].asEagerSingleton()
    bind[BeamConfig].toProvider[BeamConfigProvider].asEagerSingleton()
  }
}
