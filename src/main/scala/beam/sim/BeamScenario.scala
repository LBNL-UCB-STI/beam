package beam.sim

import beam.agentsim.agents.choice.logit.DestinationChoiceModel
import beam.agentsim.agents.choice.mode.{ModeIncentive, PtFares}
import beam.agentsim.agents.vehicles.FuelType.FuelTypePrices
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType, VehicleEnergy}
import beam.agentsim.infrastructure.taz.{H3TAZ, TAZ, TAZTreeMap}
import beam.router.Modes.BeamMode
import beam.sim.config.BeamConfig
import beam.utils.DateUtils
import com.conveyal.r5.transit.TransportNetwork
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.{Link, Network}
import org.matsim.core.utils.collections.QuadTree

import scala.collection.concurrent.TrieMap

/**
  * This holds together a couple of containers of simulation data, all of which are immutable.
  * The only semi-exception is privateVehicles: The set of its members is effectively immutable
  * after the scenario-loading phase ("what private vehicles exists"), but the BeamVehicles themselves
  * are mutable during the simulation run (fuel levels and such) -- though they are
  * not in this map in that capacity. In this map they only mean the initial fleet that is available.
  * These two concerns should be separated. (Refactor BeamVehicle.)
  *
  * Preferably only add really, actually immutable things here.
  *
  * The so far only legitimate iteration-to-iteration-mutable global thing in BEAM are the Plans,
  * and they happen to be on the MATSim Scenario for now. Everything else is kept private in
  * classes that observe the simulation.
  *
  */
case class BeamScenario(
  fuelTypePrices: FuelTypePrices,
  vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType],
  privateVehicles: TrieMap[Id[BeamVehicle], BeamVehicle],
  vehicleEnergy: VehicleEnergy,
  beamConfig: BeamConfig,
  dates: DateUtils,
  ptFares: PtFares,
  transportNetwork: TransportNetwork,
  network: Network,
  tazTreeMap: TAZTreeMap,
  linkQuadTree: QuadTree[Link],
  linkIdMapping: Map[Id[Link], Link],
  linkToTAZMapping: Map[Link, TAZ],
  modeIncentives: ModeIncentive,
  h3taz: H3TAZ
) {
  val destinationChoiceModel: DestinationChoiceModel = DestinationChoiceModel(beamConfig)

  lazy val rideHailTransitModes: Seq[BeamMode] =
    if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("all")) BeamMode.transitModes
    else if (beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.equalsIgnoreCase("mass"))
      BeamMode.massTransitModes
    else {
      beamConfig.beam.agentsim.agents.rideHailTransit.modesToConsider.toUpperCase
        .split(",")
        .map(BeamMode.fromString)
        .toSeq
        .flatten
    }
}
