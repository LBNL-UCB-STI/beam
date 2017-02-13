package beam.metasim.config

import beam.metasim.config.ConfigModule.ConfigProvider
import com.google.inject.{AbstractModule, Provider}
import com.typesafe.config.{Config, ConfigFactory}
import net.codingwell.scalaguice.ScalaModule


/**
  * Created by sfeygin on 2/6/17.
  */
object ConfigModule {

  class ConfigProvider  extends Provider[Config] {
    override def get(): Config = ConfigFactory.load()
  }
}

class ConfigModule extends AbstractModule with ScalaModule {

  override def configure(): Unit = {
    bind[Config].toProvider[ConfigProvider].asEagerSingleton()
  }
}


