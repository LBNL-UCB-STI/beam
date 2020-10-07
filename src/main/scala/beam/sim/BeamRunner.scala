package beam.sim

import scala.collection.JavaConverters._

import com.google.inject
import com.google.inject.Module
import org.matsim.core.config.{Config => MatsimConfig}

class BeamRunner(
  val matsimConfig: MatsimConfig,
  val outputDirectory: String,
  val beamServices: BeamServices,
  val injector: inject.Injector,
  runMethod: () => Unit,
  postRunner: BeamRunner => Any = _ => ()
) {

  def withPostRunner(func: BeamRunner => Any): BeamRunner = new BeamRunner(
    matsimConfig = matsimConfig,
    outputDirectory = outputDirectory,
    beamServices = beamServices,
    injector = injector,
    runMethod = runMethod,
    postRunner = func
  )

  def run(): BeamRunner = {
    runMethod()
    postRunner(this)
    this
  }

  def addModules(module: Module, others: Module*): BeamRunner = {
    injector.createChildInjector((module +: others).asJava)
    this
  }
}
