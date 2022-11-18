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
    "BEAM_FED_TAZ",
    "CHARGING_VEHICLES",
    "tcp://127.0.0.1",
    10000000,
    true,
    "zmq",
    true,
    1,
    true,
    "SPM_FED_TAZ",
    "CHARGING_COMMANDS",
    1.0
  )

  val cnmConfig_timeStepInSeconds = 60

  val numberOfFederates = 4

  val numberOfAdditionalMessages = 100
  val numberOfAdditionalFields = 100

  val selectedTazIds = (1 to numberOfFederates).map(_.toString)
  val federatesToTaz = getFederatesToTazs(selectedTazIds)

  println(s"Initialized ${federatesToTaz.length} federates, now they are going to execution mode.")
  enterExecutionMode(1.hour, federatesToTaz.map(_._1): _*)
  println("Entered execution mode.")

  val numberOfSteps = 3600
  val timeBinSize = 60 * 60 * 60 / numberOfSteps

  val timeStart: Long = Platform.currentTime
  def elapsedSecs: Long = (Platform.currentTime - timeStart) / 1000

  val reportProgressSeconds = 10
  val totalStepsProgressFromAllThreads = new AtomicInteger(0)
  val progressFutureShouldContinue = new AtomicBoolean(true)

  Future {
    while (progressFutureShouldContinue.get()) {
      val currentProgress = totalStepsProgressFromAllThreads.get()
      val executedPercentage = (currentProgress * 1.0 / (numberOfSteps * numberOfFederates) * 100).toInt
      println(s"took in total $elapsedSecs secs, executed $executedPercentage%")
      Thread.sleep(reportProgressSeconds * 1000)
    }
  }(ExecutionContext.global)

  sendMessagesInParallel(1.hour, federatesToTaz)

  println("")
  println(s"$numberOfSteps steps with $numberOfFederates federates with time bin size $timeBinSize.")
  println(s"$numberOfAdditionalFields additional fields in a message.")
  println(s"$numberOfAdditionalMessages additional messages.")

  val messageLen = BeamHelicsInterface.messageToJsonString(getMessageToSend("6")).length
  val dataSpeed = (1.0 * messageLen * numberOfFederates * numberOfSteps / elapsedSecs).toLong
  val dataSpeedFormula = s"($messageLen * $numberOfFederates * $numberOfSteps / $elapsedSecs).toInt)"
  println(s"Data transfer speed is: $dataSpeed symbols/sec $dataSpeedFormula.")
  println(s"Everything took $elapsedSecs seconds")
  println("")
  println("numberOfFederates,dataSpeed,timeTook,numberOfSteps,messageLen")
  println(s"$numberOfFederates,$dataSpeed,$elapsedSecs,$numberOfSteps,$messageLen")
  println("")

  progressFutureShouldContinue.set(false)
  federatesToTaz.map(_._1).foreach { _.close() }
  println("Federates are closed.")

  def getMessageToSend(tazId: String) = {
    (0 to numberOfAdditionalMessages)
      .map(_ => getEmptyMessage(tazId))
      .toList
  }

  def sendMessagesInParallel(
    timeout: Duration,
    federatesToTazs: Seq[(BeamHelicsInterface.BeamFederate, String)]
  ): Unit = {
    import java.util.concurrent.{SynchronousQueue, ThreadPoolExecutor, TimeUnit}
    FileUtils.using(
      new ThreadPoolExecutor(
        federatesToTazs.size,
        federatesToTazs.size * 2,
        0,
        TimeUnit.SECONDS,
        new SynchronousQueue[Runnable]
      )
    )(
      _.shutdown()
    ) { executorService =>
      implicit val ec: ExecutionContextExecutorService = ExecutionContext.fromExecutorService(executorService)
      val futureResults = federatesToTazs.map { case (beamFederate, tazId) =>
        Future {
          1 to numberOfSteps foreach { step =>
            val messageToSend = getMessageToSend(tazId)
            val messageFromHelics = beamFederate.cosimulate(step * timeBinSize, messageToSend)
            assert(messageFromHelics.size - 1 == messageToSend.size, "size should be expected")
            if (numberOfAdditionalMessages > 0) {
              assert(messageFromHelics(1).size == messageToSend.head.size, "size of child messages should be equal")
            }

            totalStepsProgressFromAllThreads.incrementAndGet()
          }
        }
      }
      Await.result(Future.sequence(futureResults), timeout)
    }
  }

  def getEmptyMessage(tazId: String): Map[String, String] = {
    val additionalMap = (0 to numberOfAdditionalFields).map { i =>
      val textFieldName = "theAnAdditionalTextField#" + i
      textFieldName -> (textFieldName + "_TextValue")
    }.toMap

    Map("tazId" -> tazId) ++ additionalMap
  }

  def getFederatesToTazs(selectedTazIds: Seq[String]): Seq[(BeamHelicsInterface.BeamFederate, String)] = {
    // the same should be in 'all_taz' in src/main/python/gemini/site_power_controller_multi_federate.py:311
    val numTAZs = selectedTazIds.size
    val fedInfo = createFedInfo(
      spmConfig.coreType,
      s"--federates=$numTAZs --broker_address=${spmConfig.brokerAddress}",
      spmConfig.timeDeltaProperty,
      spmConfig.intLogLevel
    )
    println(s"Init SitePowerManager Federates for $numTAZs TAZes...")
    selectedTazIds.map { tazIdStr =>
      val beamFedName = spmConfig.beamFederatePrefix + tazIdStr //BEAM_FED_TAZ(XXX)
      val spmFedNameSub = spmConfig.spmFederatePrefix + tazIdStr + "/" + spmConfig.spmFederateSubscription
      val federate = getFederate(
        beamFedName,
        fedInfo,
        spmConfig.bufferSize,
        cnmConfig_timeStepInSeconds,
        Some(spmConfig.beamFederatePublication),
        Some(spmFedNameSub)
      )
      (federate, tazIdStr)
    }
  }
}
