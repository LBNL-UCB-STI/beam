package beam.agentsim.agents.vehicles

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id

import scala.collection.concurrent.TrieMap
import scala.util.matching.Regex

trait VehicleManager

object VehicleManager extends LazyLogging {

  val NoManager: ReservedFor = ReservedFor(Id.create("None", classOf[VehicleManager]), TypeEnum.NoManager)
  val AnyManager: ReservedFor = ReservedFor(Id.create("Any", classOf[VehicleManager]), TypeEnum.Default)

  private val vehicleManagers: TrieMap[Id[VehicleManager], ReservedFor] = TrieMap(
    NoManager.managerId  -> NoManager,
    AnyManager.managerId -> AnyManager
  )

  val CustomReservedForRegex: Regex = """([\w-]+)\(([\w-]+)\)""".r.unanchored

  object TypeEnum extends Enumeration {
    type VehicleManagerType = Value
    val Default: VehicleManagerType = Value("default")
    val Household: VehicleManagerType = Value("household")
    val RideHail: VehicleManagerType = Value("ridehail")
    val Shared: VehicleManagerType = Value("shared")
    val Freight: VehicleManagerType = Value("freight")
    val NoManager: VehicleManagerType = Value("nomanager")
  }

  case class ReservedFor(managerId: Id[VehicleManager], managerType: TypeEnum.VehicleManagerType) {

    override def hashCode: Int = toString.hashCode

    override def toString: String = {
      managerType match {
        case TypeEnum.NoManager => NoManager.toString
        case _                  => managerType + s"($managerId)"
      }
    }
  }

  def getReservedFor(managerId: Id[VehicleManager]): Option[ReservedFor] = vehicleManagers.get(managerId)

  def createOrGetReservedFor(idString: String, vehType: TypeEnum.VehicleManagerType): ReservedFor = {
    val vehId = Id.create(idString, classOf[VehicleManager])
    if (vehicleManagers.contains(vehId) && vehicleManagers(vehId).managerType != vehType)
      throw new RuntimeException("Duplicate vehicle manager ids is not allowed")
    val reservedFor = ReservedFor(vehId, vehType)
    vehicleManagers.put(vehId, reservedFor)
    reservedFor
  }

  def createOrGetReservedFor(reservedForString: String, beamConfigMaybe: Option[BeamConfig]): Option[ReservedFor] = {
    var reservedForMaybe = reservedForString match {
      case null | "" =>
        Some(VehicleManager.AnyManager)
      case x if x == VehicleManager.AnyManager.managerId.toString =>
        Some(createOrGetReservedFor(reservedForString, TypeEnum.Default))
      case CustomReservedForRegex(kind, id) =>
        Some(createOrGetReservedFor(id, TypeEnum.withName(kind.trim.toLowerCase)))
      case _ =>
        None
    }
    if (reservedForMaybe.isEmpty && beamConfigMaybe.isDefined) {
      val cfgAgentSim = beamConfigMaybe.get.beam.agentsim
      val sharedFleets = cfgAgentSim.agents.vehicles.sharedFleets
      reservedForMaybe = reservedForString match {
        case cfgAgentSim.agents.freight.name  => Some(createOrGetReservedFor(reservedForString, TypeEnum.Freight))
        case cfgAgentSim.agents.rideHail.name => Some(createOrGetReservedFor(reservedForString, TypeEnum.RideHail))
        case reservedFor if sharedFleets.exists(_.name == reservedFor) =>
          Some(createOrGetReservedFor(reservedForString, TypeEnum.Shared))
        case _ =>
          None
      }
    }
    reservedForMaybe map { case ReservedFor(mngId, mngType) => createOrGetReservedFor(mngId.toString, mngType) }
  }
}
