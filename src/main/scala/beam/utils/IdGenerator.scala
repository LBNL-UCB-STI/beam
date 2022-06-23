package beam.utils

import java.util.concurrent.atomic.AtomicInteger

trait IdGenerator {
  private val id: AtomicInteger = new AtomicInteger(0)
  def nextId: Int = id.getAndIncrement()

  /**
    * Needs to be set at beam.sim.BeamHelper#stepsBeforeRun method in order to produce unique ids when
    * beam is run in a cluster
    * @param value the initial value
    */
  def setInitialValue(value: Int): Unit = id.set(value)
}

//put any new id generators to BeamHelper#stepsBeforeRun collection of id generators
object IdGeneratorImpl extends IdGenerator

object RideHailRequestIdGenerator extends IdGenerator

object ParkingManagerIdGenerator extends IdGenerator

object InterruptIdIdGenerator extends IdGenerator

object ReservationRequestIdGenerator extends IdGenerator

object VehicleIdGenerator extends IdGenerator
