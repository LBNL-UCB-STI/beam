package beam.sim.modules

import beam.sim.common.{GeoUtils, GeoUtilsImpl}
import com.google.inject.AbstractModule
import net.codingwell.scalaguice.ScalaModule

/**
  * BEAM
  */
class UtilsModule extends AbstractModule with ScalaModule {
  override def configure(): Unit = {
    bind[GeoUtils].to[GeoUtilsImpl].asEagerSingleton()
  }
}
