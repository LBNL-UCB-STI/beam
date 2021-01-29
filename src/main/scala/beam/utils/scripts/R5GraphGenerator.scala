package beam.utils.scripts

import java.util.concurrent.TimeUnit

import akka.actor.{ActorRef, ActorSystem, Identify, Props}
import akka.pattern._
import akka.stream.{ActorMaterializer, Materializer}
import akka.util.Timeout
import beam.router.RoutingWorker
import beam.sim.BeamHelper

import scala.concurrent.Await
import scala.concurrent.duration.Duration

/**
  * This utility pre-processes the OSM and GTFS data to produce R5-compatible graph objects and ancillary transit data
  * needed by the BEAM router.  Doing this ahead of a BEAM run permits tracking these objects as dependencies in
  * Git-LFS or DVC.
  */
object R5GraphGenerator extends BeamHelper {
  implicit val timeout: Timeout = new Timeout(600, TimeUnit.SECONDS)

  /**
    * Generates the files needed for R5 from the `osm.pbf` and `{agency}.zip` files specified according to parameters in
    * the `beam.routing` section of the config file used to run this utility. Please ensure that the `beam.routing`
    * parameters for any BEAM config files used for subsequent BEAM runs match the ones in the config
    * provided to this utility (else, you may inadvertently modify the outputs of this script).
    *
    * @param args the command-line args string, which take the form of a pair: --config and `{PATH_TO_BEAM_CONFIG}` as is
    *             standard for any BEAM run.
    */
  def main(args: Array[String]): Unit = {
    val (_, cfg) = prepareConfig(args, isConfigArgRequired = true)

    implicit val actorSystem: ActorSystem = ActorSystem("R5GraphGenerator", cfg)

    val dummyWorker: ActorRef = actorSystem.actorOf(Props(classOf[RoutingWorker], cfg), name = "dummyWorker")
    Await.result(dummyWorker ? Identify(0), Duration.Inf)

    // We've completed the task and should exit, indicating success.
    System.exit(0)

  }
}
