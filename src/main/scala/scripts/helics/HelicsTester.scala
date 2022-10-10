package scripts.helics

import com.github.beam.HelicsLoader
import com.java.helics.helicsJNI.{helics_property_int_log_level_get, helics_property_time_delta_get}
import com.java.helics.{SWIGTYPE_p_void, helics, helics_data_type}

object HelicsTester extends App {

  loadHelics()
  val fedInfo = getFederateInfo()
  val fedName = "FED_BEAM_1"
  val fedComb: SWIGTYPE_p_void = helics.helicsCreateCombinationFederate(fedName, fedInfo)

  val publicationName = "CHARGING_VEHICLES"
  val dataOutStreamHandle = Some(
    helics.helicsFederateRegisterPublication(fedComb, publicationName, helics_data_type.helics_data_type_string, "")
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
    println("Helics loaded.")
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
    helics.helicsFederateInfoSetTimeProperty(fedInfo, helics_property_time_delta_get(), timeDeltaProperty)
    helics.helicsFederateInfoSetIntegerProperty(fedInfo, helics_property_int_log_level_get(), intLogLevel)
    fedInfo
  }

  def unloadHelics(): Unit = this.synchronized {
    helics.helicsCleanupLibrary()
    helics.helicsCloseLibrary()
    println("Helics unloaded.")
  }

}
