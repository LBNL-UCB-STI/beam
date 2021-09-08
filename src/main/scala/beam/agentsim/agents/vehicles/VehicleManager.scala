package beam.agentsim.agents.vehicles

import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap

trait VehicleManager

object VehicleManager {

  val noManager: Id[VehicleManager] = Id.create("NoManager", classOf[VehicleManager])

  def createOrGetIdUsingUnique(idString: String, vehType: VehicleManagerType): Id[VehicleManager] = {
    val vehId = Id.create(idString, classOf[VehicleManager])
    if (vehicleManagers.contains(vehId) && vehicleManagers(vehId) != vehType)
      throw new RuntimeException("Duplicate vehicle manager ids is not allowed")
    vehicleManagers.put(vehId, vehType)
    vehId
  }

  def getType(vehicleManagerId: Id[VehicleManager]): VehicleManagerType = vehicleManagers(vehicleManagerId)

  sealed trait VehicleManagerType
  case object BEAMCore extends VehicleManagerType
  case object BEAMRideHail extends VehicleManagerType
  case object BEAMShared extends VehicleManagerType
  case object BEAMFreight extends VehicleManagerType
  case object Others extends VehicleManagerType

  private val vehicleManagers: TrieMap[Id[VehicleManager], VehicleManagerType] = TrieMap(noManager -> Others)
}
