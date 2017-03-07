package beam.agentsim.config

import java.io.File

import beam.agentsim.config.ConfigModule.ConfigProvider
import com.google.inject.{AbstractModule, Provider}
import com.typesafe.config.{Config, ConfigFactory}
import net.codingwell.scalaguice.ScalaModule

/**
  * Created by sfeygin on 2/6/17.
  */
object ConfigModule {
  class ConfigProvider extends Provider[Config] {
    val beamConfig: Config =ConfigFactory.parseFile(new File("config-main.conf")).resolve()
    override def get(): Config = beamConfig
  }
}

class ConfigModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[Config].toProvider[ConfigProvider].asEagerSingleton()
  }
}
