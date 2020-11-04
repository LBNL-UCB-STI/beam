package beam.agentsim.infrastructure

import beam.agentsim.agents.vehicles.BeamVehicle
import beam.agentsim.infrastructure.ChargingNetworkManager.{ChargingVehicle, ChargingZone}
import org.matsim.api.core.v01.Id
import org.matsim.core.utils.collections.QuadTree

import scala.collection.JavaConverters._
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

class ChargingNetwork(name: String, chargingStationsQTree: QuadTree[ChargingZone]) {

  val chargingStationsMap: Map[Int, ChargingZone] =
    chargingStationsQTree.values().asScala.map(s => s.parkingZoneId -> s).toMap
  private val vehiclesToCharge: TrieMap[Id[BeamVehicle], ChargingVehicle] = new TrieMap()
  def vehicles: Map[Id[BeamVehicle], BeamVehicle] = vehiclesToCharge.mapValues(_.vehicle).toMap

  val chargingQueue: mutable.PriorityQueue[(Int, Id[BeamVehicle])] =
    mutable.PriorityQueue[(Int, Id[BeamVehicle])]()(Ordering.by(_._1))
}
