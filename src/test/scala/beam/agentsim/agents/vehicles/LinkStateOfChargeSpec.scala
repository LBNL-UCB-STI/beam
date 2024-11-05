package beam.agentsim.agents.vehicles

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamHelper
import beam.utils.TestConfigUtils.testConfig
import beam.utils.{EventReader, MathUtils}
import com.typesafe.config.ConfigFactory
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.Event
import org.matsim.vehicles.Vehicle
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.BeforeAndAfterAllConfigMap
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.wordspec.AnyWordSpecLike

class LinkStateOfChargeSpec extends AnyWordSpecLike with Matchers with BeamHelper with BeforeAndAfterAllConfigMap {
  private val primaryFuelCapacityInJoule = 2.699999827e8

  "Beam" when {
    "configured to link SoC across iterations " must {
      "link electric vehicle state of charge across iterations" in {

        val iterations = 2

        val baseConf = ConfigFactory
          .parseString(s"""
            beam.agentsim.agents.rideHail.managers = [
              {
                name = "default"
                initialization.initType = "FILE"
                initialization.filePath=$${beam.inputDirectory}"/rideHailFleet.csv"
                repositioningManager.name = "DEMAND_FOLLOWING_REPOSITIONING_MANAGER"
                repositioningManager.timeout = 300
                repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemand = 1
                repositioningManager.demandFollowingRepositioningManager.numberOfClustersForDemand = 30
              },
              {
                name = "file2"
                initialization.initType = "FILE"
                initialization.filePath=$${beam.inputDirectory}"/rideHailFleet.csv"
                repositioningManager.name = "DEMAND_FOLLOWING_REPOSITIONING_MANAGER"
                repositioningManager.timeout = 300
                repositioningManager.demandFollowingRepositioningManager.sensitivityOfRepositioningToDemand = 1
                repositioningManager.demandFollowingRepositioningManager.numberOfClustersForDemand = 30
              }
            ]
            beam.agentsim.agents.rideHail.linkFleetStateAcrossIterations = true
            beam.agentsim.agents.vehicles.linkSocAcrossIterations = true
            beam.physsim.skipPhysSim = true
            beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_intercept = -1.0
            beam.agentsim.agents.modalBehaviors.multinomialLogit.params.ride_hail_pooled_intercept = -1.0
          """)
          .withFallback(testConfig("test/input/beamville/beam.conf"))
          .resolve()

        val (matsimConfig, _, _) = runBeamWithConfig(baseConf)
        val eventsPerIteration = (0 to iterations).map { i =>
          val filePath = EventReader.getEventsFilePath(matsimConfig, "events", "xml", i).getAbsolutePath
          EventReader.fromXmlFile(filePath)
        }
        val electricVehicles: IndexedSeq[Id[Vehicle]] = findAllElectricVehicles(eventsPerIteration.flatten)
        electricVehicles.size should be >= 7 withClue "Too low number of EVs, RideHail vehicles are not moving?"
        val iterationStates: IndexedSeq[Map[Id[Vehicle], (Double, Double)]] = eventsPerIteration
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
          (limitedFinalSoc shouldBe nextInitialSoc +- 0.0001) withClue
          s"Wrong initial iteration state for vehicle $vehicleId, fuel levels: ${iterationStates.mkString("\n")}"
        }
      }
    }
  }

  private def getInitialStateOfCharge(events: Seq[Event], vehicleId: Id[Vehicle]): Double = {
    events
      .collectFirst {
        case pte: PathTraversalEvent if pte.vehicleId == vehicleId =>
          pte.endLegPrimaryFuelLevel + pte.primaryFuelConsumed
      }
      .getOrElse(Double.NaN)
  }

  private def getFinalStateOfCharge(events: Seq[Event], vehicleId: Id[Vehicle]): Double = {
    events.view.reverse
      .collectFirst {
        case pte: PathTraversalEvent if pte.vehicleId == vehicleId =>
          pte.endLegPrimaryFuelLevel
        case plugOut
            if plugOut.getEventType == "ChargingPlugOutEvent"
              && plugOut.getAttributes.get("vehicle") == vehicleId.toString =>
          plugOut.getAttributes.get("primaryFuelLevel").toDouble
      }
      .getOrElse(Double.NaN)
  }

  def findAllElectricVehicles(events: IndexedSeq[Event]): IndexedSeq[Id[Vehicle]] = {
    events.collect { case pte: PathTraversalEvent if pte.primaryFuelType == "Electricity" => pte.vehicleId }.distinct
  }

}
