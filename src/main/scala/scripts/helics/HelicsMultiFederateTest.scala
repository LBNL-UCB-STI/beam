package scripts.helics

import beam.cosim.helics.BeamHelicsInterface
import beam.cosim.helics.BeamHelicsInterface._
import beam.sim.config.BeamConfig.Beam.Agentsim.ChargingNetworkManager.SitePowerManagerController
import beam.utils.FileUtils

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import scala.compat.Platform
import scala.concurrent.duration.{Duration, DurationInt}
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutorService, Future}

/*
To check how well multiple federates work and how the amount of them affect performance.
This class should use as many beam helics-related code as possible apart from broker, the broker should be run externally.
 */
object HelicsMultiFederateTest extends App {

  loadHelicsIfNotAlreadyLoaded

  val spmConfig: SitePowerManagerController = new SitePowerManagerController(
    "BEAM_FEDERATE",
    "CHARGING_VEHICLES",
    "tcp://127.0.0.1",
    10000000,
    true,
    "zmq",
    true,
    1,
    10,
    "SPM_FEDERATE",
    "CHARGING_COMMANDS",
    1.0
  )

  val cnmConfig_timeStepInSeconds = 60

  // the amount of requests in a single message sent by federate
  val numberOfMessagesPerStepPerFederate = 10

  val federateIds = (0 until spmConfig.numberOfFederates).map(_.toString)
  val federatesToIds = getFederatesToIds(federateIds)

  println(s"Initialized ${federatesToIds.length} federates, now they are going to execution mode.")
  enterExecutionMode(1.hour, federatesToIds.map(_._1): _*)
  println("Entered execution mode.")

  val numberOfSteps = 3600
  val timeBinSize = 60 * 60 * 60 / numberOfSteps

  val timeStart: Long = Platform.currentTime
  def elapsedSecs: Long = (Platform.currentTime - timeStart) / 1000

  val reportProgressSeconds = 13
  val totalStepsProgressFromAllThreads = new AtomicInteger(0)
  val progressFutureShouldContinue = new AtomicBoolean(true)

  Future {
    while (progressFutureShouldContinue.get()) {
      val currentProgress = totalStepsProgressFromAllThreads.get()
      val executedPercentage = (currentProgress * 1.0 / (numberOfSteps * spmConfig.numberOfFederates) * 100).toInt
      println(s"took in total $elapsedSecs secs, executed $executedPercentage%")
      Thread.sleep(reportProgressSeconds * 1000)
    }
  }(ExecutionContext.global)

  sendMessagesInParallel(1.hour, federatesToIds)

  progressFutureShouldContinue.set(false)
  federatesToIds.map(_._1).foreach(_.close())
  BeamHelicsInterface.closeHelics()

  val numberOfFederates = spmConfig.numberOfFederates
  println("")
  println(s"$numberOfSteps steps with $numberOfFederates federates with time bin size $timeBinSize.")

  val messageLen = BeamHelicsInterface.messageToJsonString(getMessageToSend("6")).length
  val dataSpeed = (messageLen.toDouble / elapsedSecs * numberOfFederates * numberOfSteps).toLong
  val dataSpeedFormula = s"($messageLen * $numberOfFederates * $numberOfSteps / $elapsedSecs))"
  println(s"The message len is $messageLen. Total times sent: ${totalStepsProgressFromAllThreads.get()}")
  println(s"Data transfer speed is: $dataSpeed symbols/sec $dataSpeedFormula.")
  println(s"Data transfer speed per federate: ${dataSpeed / numberOfFederates} symbols/sec.")
  println(s"Everything took $elapsedSecs seconds")
  println("")
  println("CSV report:")
  println("")
  println("numberOfFederates,numberOfSteps,messageLen,timeTook")
  println(s"$numberOfFederates,$numberOfSteps,$messageLen,$elapsedSecs")
  println("")

  def sendMessagesInParallel(
    timeout: Duration,
    federatesToIds: Seq[(BeamHelicsInterface.BeamFederate, String)]
  ): Unit = {
    import java.util.concurrent.{SynchronousQueue, ThreadPoolExecutor, TimeUnit}
    FileUtils.using(
      new ThreadPoolExecutor(
        federatesToIds.size,
        federatesToIds.size * 2,
        0,
        TimeUnit.SECONDS,
        new SynchronousQueue[Runnable]
      )
    )(
      _.shutdown()
    ) { executorService =>
      implicit val ec: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(executorService)
      val futureResults = federatesToIds.map { case (beamFederate, federateId) =>
        Future {
          1 to numberOfSteps foreach { step =>
            val messageToSend = getMessageToSend(federateId)
            val messageFromHelics = beamFederate.cosimulate(step * timeBinSize, messageToSend)

            assert(messageFromHelics.size - 1 == messageToSend.size, s"Size should be expected.")
            if (numberOfMessagesPerStepPerFederate > 0) {
              assert(messageFromHelics(1).size == messageToSend.head.size, s"Size of child messages should be equal.")
            }

            totalStepsProgressFromAllThreads.incrementAndGet()
          }
        }
      }
      Await.result(Future.sequence(futureResults), timeout)
    }
  }

  def getMessageToSend(federateId: String): List[Map[String, String]] = {
    def getMessage(federateId: String): Map[String, String] = {
      Map(
        "federateId" -> federateId,
        "tazId"      -> scala.util.Random.nextInt(5).toString,
        "vehicleId"  -> s"vehicle_${scala.util.Random.nextInt(100)}"
      )
    }

    (0 until numberOfMessagesPerStepPerFederate)
      .map(_ => getMessage(federateId))
      .toList
  }

  def getFederatesToIds(federateIds: Seq[String]): Seq[(BeamHelicsInterface.BeamFederate, String)] = {
    // the same number of federates should be in according site_power_controller_*.py script
    val numFederates = federateIds.size
    val fedInfo = createFedInfo(
      spmConfig.coreType,
      s"--federates=$numFederates --broker_address=${spmConfig.brokerAddress}",
      spmConfig.timeDeltaProperty,
      spmConfig.intLogLevel
    )
    println(s"Init $numFederates SitePowerManager Federates...")
    federateIds.map { federateId =>
      val beamFedName = spmConfig.beamFederatePrefix + federateId
      val spmFedNameSub = spmConfig.spmFederatePrefix + federateId + "/" + spmConfig.spmFederateSubscription
      val federate = getFederate(
        beamFedName,
        fedInfo,
        spmConfig.bufferSize,
        cnmConfig_timeStepInSeconds,
        Some(spmConfig.beamFederatePublication),
        Some(spmFedNameSub)
      )
      (federate, federateId)
    }
  }
}
