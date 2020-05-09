package beam.physsim.jdeqsim

import java.util.concurrent.atomic.AtomicBoolean

import com.typesafe.scalalogging.StrictLogging
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.core.gbl.Gbl
import org.matsim.core.mobsim.jdeqsim.{Message, Road, Scheduler}

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random
import scala.util.control.NonFatal

// Changes in MATSim are here: https://github.com/wrashid/matsim/tree/art/parallel-jdeqsim
class ParallelScheduler(simulationEndTime: Double, clusterName: String)(implicit ex: ExecutionContext)
    extends Scheduler
    with StrictLogging {
  private val simulationStartTime = System.currentTimeMillis
  private var hourlyLogTime: Double = 3600

  private val queue: LockingMessageQueue = new LockingMessageQueue()

  private var linkIdToScheduler: Map[Id[Link], ParallelScheduler] = _

  private val shouldStop: AtomicBoolean = new AtomicBoolean(false)
  private val hasReachedTheEndOfSimulation: AtomicBoolean = new AtomicBoolean(false)

  private var simFuture: Future[Unit] = _

  private var simThread: Thread = _

  def setSchedulers(linkIdToScheduler: Map[Id[Link], ParallelScheduler]): Unit = {
    this.linkIdToScheduler = linkIdToScheduler
  }

//  def resolveScheduler(m: Message): Scheduler = {
//    m match {
//      case endLegMessage: EndLegMessage =>
//        val road: Road = endLegMessage.getReceivingUnit.asInstanceOf[Road]
//        linkIdToScheduler(road.getLink.getId)
//      case x =>
//        val road: Road = x.getReceivingUnit.asInstanceOf[Road]
//        linkIdToScheduler(road.getLink.getId)
//    }
//  }

  def resolveScheduler(m: Message): Scheduler = {
    m.getReceivingUnit.asInstanceOf[Road].getScheduler
  }

  override def schedule(m: Message): Unit = {
    val actualScheduler = resolveScheduler(m)
    if (actualScheduler == this) {
      queue.putMessage(m)
    } else {
      // FIXME? Make a new copy of vehicle and replace it in original message
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
    logger.info(s"startSimulation $clusterName")
    val myself = this
    val thread = new Thread(){
      override def run(): Unit = {
        setName(clusterName)
        logger.info(s"started $clusterName")

        try {
          var cnt: Long = 0
          var simTime: Double = 0
          while (!shouldStop.get()) {
            val m = queue.getNextMessage
            if (m != null) {
              require(m.getReceivingUnit.getScheduler == myself)
              simTime = m.getMessageArrivalTime
              try {
                m.processEvent()
                m.handleMessage()
              }
              catch {
                case NonFatal(ex) =>
                  logger.error(s"Something wrong: ${ex.getMessage}", ex)
              }
            }
            else {
              cnt += 1
              if (cnt % 10 == 0) {
                logger.info(s"$clusterName has no message, sleeping")
              }
              val rnd = 50 + Random.nextInt(200)
              Thread.sleep(rnd)
            }
            printLog(simTime)
          }
          hasReachedTheEndOfSimulation.set(simTime > simulationEndTime)
        }
        catch {
          case NonFatal(ex) =>
            logger.error(s"Something wrong: ${ex.getMessage}", ex)
        }
      }
    }
    thread.start()
    simThread = thread
  }

//  override def startSimulation(): Unit = {
//    logger.info(s"startSimulation $clusterName")
//
//    simFuture = Future {
//      logger.info(s"startSimulation inside the Future $clusterName")
//
//      var cnt: Long = 0
//      var cnt2: Long = 0
//      var shouldBreak: Boolean = false
//      var simTime: Double = 0
//      while (!shouldStop.get()) {
//        cnt2 += 1
//        val m = queue.getNextMessage
////        if (cnt2 % 1000 == 0) {
////          logger.info(s"$clusterName. Message is $m")
////        }
////        if (queue.getQueueSize > 0) {
////          if (shouldBreak) {
////            DebugLib.emptyFunctionForSettingBreakPoint()
////          }
////        }
//        if (m != null) {
//          simTime = m.getMessageArrivalTime
//          m.processEvent()
//          m.handleMessage()
//        } else {
//          cnt += 1
//          if (cnt % 10 == 0) {
//            logger.info(s"$clusterName has no message, sleeping")
//          }
//          val rnd = 50 + Random.nextInt(200)
//          Thread.sleep(rnd)
//        }
//        printLog(simTime)
//      }
//      hasReachedTheEndOfSimulation.set(simTime > simulationEndTime)
//    }
//  }

  override def getSimTime: Double = 0.0

  def queuedMessages: Int = queue.getQueueSize

  def isFinished: Boolean = hasReachedTheEndOfSimulation.get()

  private def printLog(simTime: Double): Unit = { // print output each hour
    if (simTime / hourlyLogTime > 1) {
      hourlyLogTime = simTime + 3600
      logger.info(
        "Simulation at " + simTime / 3600 + "[h]; s/r:" + simTime / (System.currentTimeMillis - simulationStartTime) * 1000
      )
      Gbl.printMemoryUsage()
    }
  }
}
