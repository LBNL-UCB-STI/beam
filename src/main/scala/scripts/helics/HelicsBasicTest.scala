package scripts.helics

import com.github.beam.HelicsLoader
import com.java.helics.helicsJNI.{HELICS_PROPERTY_INT_LOG_LEVEL_get, HELICS_PROPERTY_TIME_DELTA_get}
import com.java.helics.{helics, HelicsDataTypes, SWIGTYPE_p_void}

/*
To do a basic test with as few dependencies on BEAM code as possible.
The test require the helics broker to be run externally, for example by a python script.
 */
object HelicsBasicTest extends App {

  loadHelics()
  val fedInfo = getFederateInfo()
  val fedName = "SOME_RANDOM_FEDERATE_NAME"
  val fedComb: SWIGTYPE_p_void = helics.helicsCreateCombinationFederate(fedName, fedInfo)

  val publicationName = "CHARGING_VEHICLES_PUBLICATION_42"

  val dataOutStreamHandle = Some(
    helics.helicsFederateRegisterPublication(fedComb, publicationName, HelicsDataTypes.HELICS_DATA_TYPE_STRING, "")
  )

  // val subscriptionName = "CHARGING_VEHICLES"
  // val dataInStreamHandle = Some(helics.helicsFederateRegisterSubscription(fedComb, subscriptionName, ""))

  helics.helicsFederateEnterExecutingMode(fedComb)

  publishAndSync(3, 100)
  publishAndSync(3, 200)
  publishAndSync(3, 300)

  unloadHelics()

  def publishAndSync(secondsToWaitAfter: Int = 0, timeToSync: Int = 0): Unit = {
    dataOutStreamHandle.foreach(helics.helicsPublicationPublishString(_, List("foo", 123456).mkString(",")))

    if (secondsToWaitAfter > 0) Thread.sleep(secondsToWaitAfter * 1000L)

    if (timeToSync > 0) {
      var currentTime = -1.0
      while (currentTime < timeToSync) currentTime = helics.helicsFederateRequestTime(fedComb, timeToSync)
      println("Message published and time synced.")
    } else {
      println("Message published.")
    }
  }

  def loadHelics(): Unit = {
    HelicsLoader.load()
    println(s"Helics loaded (version: ${helics.helicsGetVersion()})")
  }

  def getFederateInfo(
    coreType: String = "zmq",
    coreInitString: String = "--federates=1 --broker_address=tcp://127.0.0.1",
    timeDeltaProperty: Double = 1.0,
    intLogLevel: Int = 1
  ): SWIGTYPE_p_void = {
    val fedInfo: SWIGTYPE_p_void = helics.helicsCreateFederateInfo()
    helics.helicsFederateInfoSetCoreTypeFromString(fedInfo, coreType)
    helics.helicsFederateInfoSetCoreInitString(fedInfo, coreInitString)
    helics.helicsFederateInfoSetTimeProperty(fedInfo, HELICS_PROPERTY_TIME_DELTA_get(), timeDeltaProperty)
    helics.helicsFederateInfoSetIntegerProperty(fedInfo, HELICS_PROPERTY_INT_LOG_LEVEL_get(), intLogLevel)
    fedInfo
  }

  def unloadHelics(): Unit = this.synchronized {
    helics.helicsCleanupLibrary()
    helics.helicsCloseLibrary()
    println("Helics unloaded.")
  }

}
