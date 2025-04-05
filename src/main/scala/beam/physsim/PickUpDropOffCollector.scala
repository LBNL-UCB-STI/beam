package beam.physsim

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.events.PathTraversalEvent
import beam.router.Modes
import beam.sim.config.BeamConfig
import com.typesafe.scalalogging.LazyLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.events.{Event, PersonEntersVehicleEvent, PersonLeavesVehicleEvent}
import org.matsim.core.controler.events.{IterationEndsEvent, IterationStartsEvent}
import org.matsim.core.controler.listener.{IterationEndsListener, IterationStartsListener}
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.vehicles.Vehicle

import scala.annotation.tailrec
import scala.collection.mutable

class PickUpDropOffCollector(vehicleTypes: Map[Id[BeamVehicleType], BeamVehicleType])
    extends BasicEventHandler
    with IterationStartsListener
    with LazyLogging {

  private val vehicleToPickUpsDropOffs: mutable.HashMap[Id[Vehicle], VehiclePickUpsDropOffs] = mutable.HashMap.empty
  private val personsPickUps: mutable.ListBuffer[PersonPickUp] = mutable.ListBuffer.empty
  private val personsDropOffs: mutable.ListBuffer[PersonDropOff] = mutable.ListBuffer.empty

  private val vehiclesToIgnore: mutable.HashSet[Id[Vehicle]] = mutable.HashSet.empty
  private val vehicleTypeToCAV: mutable.HashMap[String, Boolean] = mutable.HashMap.empty

  private def isRideHail(vehicleId: Id[Vehicle]): Boolean = vehicleId.toString.startsWith("rideHailVehicle")

  private var maybePickUpDropOffHolder: Option[PickUpDropOffHolder] = None
  logger.info("PickUp DropOff Collector initialized")

  private def isCAV(vehicleType: String): Boolean = {
    vehicleTypeToCAV.get(vehicleType) match {
      case None =>
        val beamVehicleTypeId = Id.create(vehicleType, classOf[BeamVehicleType])
        val isCaccEnabled = vehicleTypes.get(beamVehicleTypeId) match {
          case Some(beamVehicleType) => beamVehicleType.isCaccEnabled
          case _                     => false
        }

        vehicleTypeToCAV.put(vehicleType, isCaccEnabled)
        isCaccEnabled

      case Some(isCaccEnabled) => isCaccEnabled
    }
  }

  private def isRideHailOrCAV(pte: PathTraversalEvent): Boolean = {
    val mode = pte.mode
    mode match {
      case Modes.BeamMode.CAR if isRideHail(pte.vehicleId) || isCAV(pte.vehicleType) => true
      case Modes.BeamMode.CAV                                                        => true
      case Modes.BeamMode.RIDE_HAIL                                                  => true
      case Modes.BeamMode.RIDE_HAIL_POOLED                                           => true
      case Modes.BeamMode.RIDE_HAIL_TRANSIT                                          => true
      case _                                                                         => false
    }
  }

  override def handleEvent(event: Event): Unit = {
    def vehicleNotIgnored(vehicleId: Id[Vehicle]): Boolean = {
      val isIgnored = vehiclesToIgnore.contains(vehicleId)
      !isIgnored
    }

    event match {
      case pte: PathTraversalEvent if isRideHailOrCAV(pte) && vehicleNotIgnored(pte.vehicleId) =>
        vehicleToPickUpsDropOffs.get(pte.vehicleId) match {
          case Some(pickUpsDropOffs) => pickUpsDropOffs.addPickUpDropOff(pte)
          case None =>
            val pickUpsDropOffs = VehiclePickUpsDropOffs()
            pickUpsDropOffs.addPickUpDropOff(pte)
            vehicleToPickUpsDropOffs(pte.vehicleId) = pickUpsDropOffs
        }
      case pte: PathTraversalEvent => vehiclesToIgnore.add(pte.vehicleId)

      case plv: PersonLeavesVehicleEvent if plv.getTime > 0 && vehicleNotIgnored(plv.getVehicleId) =>
        personsDropOffs += PersonDropOff(plv)
      case pev: PersonEntersVehicleEvent if pev.getTime > 0 && vehicleNotIgnored(pev.getVehicleId) =>
        personsPickUps += PersonPickUp(pev)

      case _ =>
    }
  }

  override def notifyIterationStarts(event: IterationStartsEvent): Unit = {
    maybePickUpDropOffHolder = None

    vehicleToPickUpsDropOffs.clear()
    personsDropOffs.clear()
    personsPickUps.clear()
  }

  def getPickUpDropOffHolder(beamConfig: BeamConfig): PickUpDropOffHolder = {
    if (maybePickUpDropOffHolder.nonEmpty) {
      maybePickUpDropOffHolder.get
    } else {
      val pickUpDropOffHolder = new PickUpDropOffHolder(getLinkToPickUpsDropOffs, beamConfig)
      maybePickUpDropOffHolder = Some(pickUpDropOffHolder)
      pickUpDropOffHolder
    }
  }

  def getLinkToPickUpsDropOffs: mutable.HashMap[Int, LinkPickUpsDropOffs] = {
    val linkToPickUpsDropOffs = mutable.HashMap.empty[Int, LinkPickUpsDropOffs]

    def getLinkToPickUpsDropOffs(link: Int): LinkPickUpsDropOffs = {
      linkToPickUpsDropOffs.get(link) match {
        case Some(linkPickUpsDropOffs) => linkPickUpsDropOffs
        case None =>
          val linkPickUpsDropOffs = LinkPickUpsDropOffs(link)
          linkToPickUpsDropOffs(link) = linkPickUpsDropOffs
          linkPickUpsDropOffs
      }
    }

    def addLinkPickUp(link: Int, time: Double): Unit = {
      getLinkToPickUpsDropOffs(link).timeToPickUps.addTimeToValue(time, 1)
    }

    def addLinkDropOff(link: Int, time: Double): Unit = {
      getLinkToPickUpsDropOffs(link).timeToDropOffs.addTimeToValue(time, 1)
    }

    val filteredVehiclePickUpsDropOffs = vehicleToPickUpsDropOffs
      .filter { case (vehicleId, _) => !vehiclesToIgnore.contains(vehicleId) }

    personsPickUps.foreach { personPickUp =>
      filteredVehiclePickUpsDropOffs.get(personPickUp.vehicleId) match {
        case Some(vehiclePickUpsDropOffs) =>
          vehiclePickUpsDropOffs.pickUpTimeToLink.getClosestValue(personPickUp.time) match {
            case Some(link) => addLinkPickUp(link, personPickUp.time)
            case None =>
              logger.error(
                s"Missing vehicle pick up point from PathTraversals for vehicle ${personPickUp.vehicleId} at time ${personPickUp.time}. All data: $vehiclePickUpsDropOffs"
              )
          }
        case None =>
      }
    }

    personsDropOffs.foreach { personDropOff =>
      filteredVehiclePickUpsDropOffs.get(personDropOff.vehicleId) match {
        case Some(vehiclePickUpsDropOffs) =>
          vehiclePickUpsDropOffs.dropOffTimeToLink.getClosestValue(personDropOff.time) match {
            case Some(link) => addLinkDropOff(link, personDropOff.time)
            case None =>
              logger.error(
                s"Missing vehicle drop off point from PathTraversals for vehicle ${personDropOff.vehicleId} at time ${personDropOff.time}. All data: $vehiclePickUpsDropOffs"
              )
          }
        case None =>
      }
    }

    linkToPickUpsDropOffs
  }
}

case class LinkPickUpsDropOffs(
  linkId: Int,
  timeToPickUps: TimeToValueCollection = new TimeToValueCollection(),
  timeToDropOffs: TimeToValueCollection = new TimeToValueCollection()
) {
  override def toString: String = s"link $linkId has pickUps: $timeToPickUps dropOffs: $timeToDropOffs"
}

object PersonPickUp {

  def apply(pev: PersonEntersVehicleEvent): PersonPickUp =
    new PersonPickUp(pev.getTime, pev.getVehicleId)
}
case class PersonPickUp(time: Double, vehicleId: Id[Vehicle])

object PersonDropOff {

  def apply(plv: PersonLeavesVehicleEvent): PersonDropOff =
    new PersonDropOff(plv.getTime, plv.getVehicleId)
}
case class PersonDropOff(time: Double, vehicleId: Id[Vehicle])

case class VehiclePickUpsDropOffs(
  pickUpTimeToLink: TimeToValueCollection = new TimeToValueCollection(),
  dropOffTimeToLink: TimeToValueCollection = new TimeToValueCollection()
) {

  def addPickUpDropOff(pte: PathTraversalEvent): Unit = {
    pickUpTimeToLink.addTimeToValue(pte.departureTime, pte.linkIds.head)
    dropOffTimeToLink.addTimeToValue(pte.arrivalTime, pte.linkIds.last)
  }

  override def toString: String = s"PickUps: $pickUpTimeToLink DropOffs: $dropOffTimeToLink"
}

class TimeToValueCollection(
  val times: mutable.ArrayBuffer[Double] = mutable.ArrayBuffer.empty,
  val values: mutable.ArrayBuffer[Int] = mutable.ArrayBuffer.empty
) {

  def addTimeToValue(time: Double, value: Int): Unit = {
    if (times.isEmpty || times.last < time) {
      times.append(time)
      values.append(value)
    } else {
      var idx = times.length - 1
      while (idx > 0 && times(idx) > time) { idx -= 1 }
      if (times(idx) < time) { idx += 1 }
      times.insert(idx, time)
      values.insert(idx, value)
    }
  }

  @tailrec
  final def binarySearchTime(time: Double, leftIdx: Int, rightIdx: Int): Int = {
    if (leftIdx >= rightIdx) {
      rightIdx
    } else {
      val centerIdx: Int = leftIdx + (rightIdx - leftIdx) / 2
      if (time > times(centerIdx)) {
        binarySearchTime(time, centerIdx + 1, rightIdx)
      } else {
        binarySearchTime(time, leftIdx, centerIdx)
      }
    }
  }

  def getClosestTimeIndex(time: Double): Option[Int] = {
    if (times.isEmpty) {
      None
    } else if (times.size == 1) {
      Some(0)
    } else {
      def chooseIndexOfClosestValue(leftIdx: Int, rightIdx: Int): Int = {
        val deltaLeft = Math.abs(times(leftIdx) - time)
        val deltaRight = Math.abs(times(rightIdx) - time)
        if (deltaLeft < deltaRight) {
          leftIdx
        } else {
          rightIdx
        }
      }

      val timeIdx = binarySearchTime(time, 0, times.size - 1)
      if (times(timeIdx) == time) {
        Some(timeIdx)
      } else {
        val closestIdx = if (timeIdx == 0) {
          chooseIndexOfClosestValue(0, 1)
        } else if (timeIdx == times.size - 1) {
          chooseIndexOfClosestValue(times.size - 2, times.size - 1)
        } else if (time > times(timeIdx)) {
          chooseIndexOfClosestValue(timeIdx, timeIdx + 1)
        } else {
          chooseIndexOfClosestValue(timeIdx - 1, timeIdx)
        }
        Some(closestIdx)
      }
    }
  }

  def getClosestValue(time: Double, maxTimeDifference: Double): Option[Int] = {
    getClosestTimeIndex(time) match {
      case Some(idx) if Math.abs(times(idx) - time) <= maxTimeDifference => Some(values(idx))
      case _                                                             => None
    }
  }

  def getClosestValue(time: Double): Option[Int] = {
    getClosestTimeIndex(time) match {
      case Some(idx) => Some(values(idx))
      case _         => None
    }
  }

  override def toString: String = times.indices.map(idx => s"${times(idx)}:${values(idx)}").mkString(" ")
}
