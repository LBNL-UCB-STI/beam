package beam.sim.config

import com.google.inject._
import com.typesafe.config.{Config => TypesafeConfig}
import net.codingwell.scalaguice.ScalaModule

class ConfigModule(val typesafeConfig: TypesafeConfig, beamConfig: BeamConfig) extends AbstractModule with ScalaModule {

  @Provides @Singleton
  def getTypesafeConfig: TypesafeConfig = {
    typesafeConfig
  }

  @Provides @Singleton
  def beamConfig(): BeamConfig =
    beamConfig

  override def configure(): Unit = {}

}
