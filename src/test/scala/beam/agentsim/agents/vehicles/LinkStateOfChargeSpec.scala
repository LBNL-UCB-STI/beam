package beam.agentsim.agents.vehicles

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{EventReader, MathUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.wordspec.AnyWordSpecLike

import scala.util.{Failure, Success, Try}

class LinkStateOfChargeSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {
  private val primaryFuelCapacityInJoule = 2.699999827e8

  "Beam" when {
    "configured to link SoC across iterations " must {
      "link electric vehicle state of charge across iterations" in {

        val iterations = 2

        val baseConf = ConfigFactory
          .parseString(s"""
           beam.agentsim.agents.rideHail.initialization.initType = "FILE"
           beam.agentsim.agents.rideHail.initialization.filePath=$${beam.inputDirectory}"/rideHailFleet.csv"
           beam.agentsim.agents.rideHail.linkFleetStateAcrossIterations = true
           beam.agentsim.agents.vehicles.linkSocAcrossIterations = true
           beam.physsim.skipPhysSim = true
         """)
          .withFallback(testConfig("beam.sim.test/input/beamville/beam.conf"))
          .resolve()

        val (matsimConfig, _, _) = runBeamWithConfig(baseConf)
        val eventsPerIteration = (0 to iterations).map { i =>
          val filePath = EventReader.getEventsFilePath(matsimConfig, "events", "xml", i).getAbsolutePath
          EventReader.fromXmlFile(filePath)
        }
        val electricVehicles = IndexedSeq("4", "8", "9") ++ IndexedSeq(
          "rideHailVehicle-rideHailVehicle-49@default",
          "rideHailVehicle-rideHailVehicle-20@default",
          "rideHailVehicle-rideHailVehicle-28@default",
          "rideHailVehicle-rideHailVehicle-40@default",
          "rideHailVehicle-rideHailVehicle-41@default",
          "rideHailVehicle-rideHailVehicle-31@default",
          "rideHailVehicle-rideHailVehicle-38@default",
          "rideHailVehicle-rideHailVehicle-3@default",
          "rideHailVehicle-rideHailVehicle-7@default",
          "rideHailVehicle-rideHailVehicle-29@default"
        )
        val iterationStates: IndexedSeq[Map[String, (Double, Double)]] = eventsPerIteration
          .map(events =>
            electricVehicles
              .map(vehicle =>
                vehicle -> (getInitialStateOfCharge(events, vehicle), getFinalStateOfCharge(events, vehicle))
              )
              .toMap
          )
        for {
          vehicleId <- electricVehicles
          activeIterationsOfVehicle = iterationStates.map(_(vehicleId)).filterNot(_._1.isNaN)
          twoIterations <- activeIterationsOfVehicle.sliding(2).toIndexedSeq if activeIterationsOfVehicle.size > 1
          (_, finalLevel) = twoIterations.head
          (initialNextIterationLevel, _) = twoIterations.last
        } yield {
          //final SOC might be greater then 1.0 because of too long charging sessions
          val limitedFinalSoc = MathUtils.clamp(finalLevel / primaryFuelCapacityInJoule, 0, 1.0)
          val nextInitialSoc = initialNextIterationLevel / primaryFuelCapacityInJoule
          Try(limitedFinalSoc shouldBe nextInitialSoc +- 0.0001) match {
            case Failure(exception) =>
              logger.error(
                "Wrong initial iteration state for vehicle {}, fuel levels: {}",
                vehicleId,
                iterationStates.mkString("\n")
              )
              throw exception
            case Success(_) =>
          }
        }
      }
    }
  }

  private def getInitialStateOfCharge(events: Seq[Event], vehicleIdStr: String): Double = {
    val vehicleId = Id.createVehicleId(vehicleIdStr)
    events
      .collectFirst {
        case pte: PathTraversalEvent if pte.vehicleId == vehicleId =>
          pte.endLegPrimaryFuelLevel + pte.primaryFuelConsumed
      }
      .getOrElse(Double.NaN)
  }

  private def getFinalStateOfCharge(events: Seq[Event], vehicleIdStr: String): Double = {
    val vehicleId = Id.createVehicleId(vehicleIdStr)
    events.view.reverse
      .collectFirst {
        case pte: PathTraversalEvent if pte.vehicleId == vehicleId =>
          pte.endLegPrimaryFuelLevel
        case plugOut if isEventOfTypeAndVehicle(plugOut, "ChargingPlugOutEvent", vehicleIdStr) =>
          plugOut.getAttributes.get("primaryFuelLevel").toDouble
      }
      .getOrElse(Double.NaN)
  }

  private def isEventOfTypeAndVehicle(event: Event, eventType: String, vehicle: String) = {
    event.getEventType == eventType && event.getAttributes.get("vehicle") == vehicle
  }
}
