package beam.sim

import java.util.concurrent.TimeUnit

import akka.util.Timeout
import akka.pattern.ask
import beam.agentsim.scheduler.BeamAgentScheduler.StartSchedule
import com.google.inject.Inject
import org.apache.log4j.Logger
import org.matsim.core.mobsim.framework.Mobsim

import scala.concurrent.Await


/**
  * BEAM
  */

class BeamMobsim @Inject()(val beamServices: BeamServices) extends Mobsim {
  private implicit val timeout = Timeout(50000, TimeUnit.SECONDS)

  private val log = Logger.getLogger(classOf[BeamMobsim])

  override def run() = {
    log.info("Running BEAM Mobsim")
    Await.result(beamServices.schedulerRef ? StartSchedule(0), timeout.duration)
  }
}
