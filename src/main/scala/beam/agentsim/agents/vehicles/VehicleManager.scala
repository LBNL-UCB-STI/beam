package beam.agentsim.agents.vehicles

import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import beam.agentsim.agents.freight.input.FreightReader.CARRIER_ID_PREFIX

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

  private val CustomReservedForRegex: Regex = """([\w-]+)\(([\w-]+)\)""".r.unanchored

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

    override val hashCode: Int = toString.hashCode

    override def toString: String = {
      managerType match {
        case TypeEnum.NoManager => managerType.toString
        case _                  => managerType + s"($managerId)"
      }
    }
  }

  def getReservedFor(managerId: Id[VehicleManager]): Option[ReservedFor] = vehicleManagers.get(managerId)

  def createOrGetReservedFor(
    idString: String,
    vehTypeMaybe: Option[TypeEnum.VehicleManagerType] = None
  ): ReservedFor = {
    val vehId = Id.create(idString, classOf[VehicleManager])
    val vehType = vehTypeMaybe.getOrElse {
      if (idString.startsWith(CARRIER_ID_PREFIX))
        TypeEnum.Freight
      else if (idString.startsWith("ridehail"))
        TypeEnum.RideHail
      else if (idString.startsWith("shared"))
        TypeEnum.Shared
      else
        TypeEnum.Household
    }
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
        Some(createOrGetReservedFor(reservedForString, Some(TypeEnum.Default)))
      case CustomReservedForRegex(kind, id) =>
        Some(createOrGetReservedFor(id, Some(TypeEnum.withName(kind.trim.toLowerCase))))
      case _ =>
        None
    }
    if (reservedForMaybe.isEmpty && beamConfigMaybe.isDefined) {
      val cfgAgentSim = beamConfigMaybe.get.beam.agentsim
      val sharedFleets = cfgAgentSim.agents.vehicles.sharedFleets
      val rideHailManagers = cfgAgentSim.agents.rideHail.managers
      reservedForMaybe = reservedForString match {
        case cfgAgentSim.agents.freight.name => Some(createOrGetReservedFor(reservedForString, Some(TypeEnum.Freight)))
        case reservedFor if rideHailManagers.exists(_.name == reservedFor) =>
          Some(createOrGetReservedFor(reservedForString, Some(TypeEnum.RideHail)))
        case reservedFor if sharedFleets.exists(_.name == reservedFor) =>
          Some(createOrGetReservedFor(reservedForString, Some(TypeEnum.Shared)))
        case _ =>
          None
      }
    }
    reservedForMaybe
  }

  def reserveForToString(reservedFor: ReservedFor): String = {
    reservedFor match {
      case NoManager  => "None"
      case AnyManager => "Any"
      case x          => x.toString
    }
  }
}
