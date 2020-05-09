package beam.physsim.jdeqsim

import java.util.concurrent.atomic.AtomicBoolean

import beam.utils.DebugLib
import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.gbl.Gbl
import org.matsim.core.mobsim.jdeqsim.{EndLegMessage, Message, Road, Scheduler}

import scala.concurrent.{ExecutionContext, Future}

// Changes in MATSim are here: https://github.com/wrashid/matsim/tree/art/parallel-jdeqsim
class ParallelScheduler(queue: LockingMessageQueue, simulationEndTime: Double)(implicit ex: ExecutionContext)
    extends Scheduler
    with StrictLogging {
  private var simTime: Double = 0
  private val simulationStartTime = System.currentTimeMillis
  private var hourlyLogTime: Double = 3600
  private var linkIdToScheduler: Map[Id[Link], ParallelScheduler] = _

  private val shouldStop: AtomicBoolean = new AtomicBoolean(false)
  private val hasReachedTheEndOfSimulation: AtomicBoolean = new AtomicBoolean(false)

  private var simFuture: Future[Unit] = _

  def this(queue: LockingMessageQueue)(implicit ex: ExecutionContext) {
    this(queue, Double.MaxValue)
  }

  def setSchedulers(linkIdToScheduler: Map[Id[Link], ParallelScheduler]): Unit = {
    this.linkIdToScheduler = linkIdToScheduler
  }

  def resolveScheduler(m: Message): ParallelScheduler = {
    m match {
      case endLegMessage: EndLegMessage =>
        val road: Road = endLegMessage.getReceivingUnit.asInstanceOf[Road]
        linkIdToScheduler(road.getLink.getId)
      case x =>
        val road: Road = x.getReceivingUnit.asInstanceOf[Road]
        linkIdToScheduler(road.getLink.getId)
    }
  }

  override def schedule(m: Message): Unit = {
    val actualScheduler = resolveScheduler(m)
    if (actualScheduler == this) {
      queue.putMessage(m)
    } else {
      // TODO Make a new copy of vehicle and replace it in original message
      actualScheduler.schedule(m)
    }
  }

  override def unschedule(m: Message): Unit = {
    val actualScheduler = resolveScheduler(m)
    if (actualScheduler == this) {
      queue.removeMessage(m)
    } else {
      actualScheduler.unschedule(m)
    }
  }

  def stop(): Unit = {
    shouldStop.set(true)
  }

  override def startSimulation(): Unit = {
    simFuture = Future {
      var shouldBreak: Boolean = false
      while (!shouldStop.get()) {
        val m = queue.getNextMessage
        if (queue.getQueueSize > 0) {
          if (shouldBreak) {
            DebugLib.emptyFunctionForSettingBreakPoint()
          }
        }
        if (m != null) {
          simTime = m.getMessageArrivalTime
          m.processEvent()
          m.handleMessage()
        } else {
          Thread.sleep(1)
        }
        printLog()
      }
      hasReachedTheEndOfSimulation.set(simTime > simulationEndTime)
    }
  }

  override def getSimTime: Double = simTime

  def queuedMessages: Int = queue.getQueueSize

  def isFinished: Boolean = hasReachedTheEndOfSimulation.get()

  private def printLog(): Unit = { // print output each hour
    if (simTime / hourlyLogTime > 1) {
      hourlyLogTime = simTime + 3600
      logger.info(
        "Simulation at " + simTime / 3600 + "[h]; s/r:" + simTime / (System.currentTimeMillis - simulationStartTime) * 1000
      )
      Gbl.printMemoryUsage()
    }
  }
}
