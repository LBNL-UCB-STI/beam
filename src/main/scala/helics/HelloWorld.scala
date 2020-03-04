package helics

//import com.java.helics.helics

import java.util.concurrent.TimeUnit


object HelloWorld {

  def main(args: Array[String]): Unit = {

    //import com.java.helics.SWIGTYPE_p_void
    import com.java.helics._
    import com.java.helics.helicsJNI._
    System.loadLibrary("JNIhelics")
    //System.loadLibrary("helicsSharedLib");

    System.out.println(helics.helicsGetVersion)
    System.out.println("pi_receiver_java")
    val fi = helics.helicsCreateFederateInfo
    val coreInit = "--federates=1"
    val fedName = "TestB Federate"
    val coreName = "zmq"
    val deltat = 0.01
    var currenttime = 0.0
    var value = 0.0
    //var `val` = 0.0
    val grantedtime = Array(0.0)
    helics.helicsFederateInfoSetCoreName(fi, fedName)
    helics.helicsFederateInfoSetCoreTypeFromString(fi, coreName)
    helics.helicsFederateInfoSetCoreInitString(fi, coreInit)

    //helicsJNI.helics_property_time_input_delay_get()
    helics.helicsFederateInfoSetTimeProperty(fi, helics_property_time_input_delay_get(), deltat)
    helics.helicsFederateInfoSetIntegerProperty(fi, helics_property_int_log_level_get(), 1)
    //helics.helicsFederateInfoFree(fi);

    val vFed = helics.helicsCreateValueFederate(fedName, fi)

    val sub = helics.helicsFederateRegisterSubscription(vFed, "testA", "")

    helics.helicsFederateEnterInitializingMode(vFed)
    helics.helicsFederateEnterExecutingMode(vFed)
    currenttime = helics.helicsFederateRequestTime(vFed, 100)

    var isupdated = helics.helicsInputIsUpdated(sub)

    while ( {
      currenttime <= 100
    }) {
      currenttime = helics.helicsFederateRequestTime(vFed, 100)
      //            try {
      //                TimeUnit.SECONDS.sleep(1);
      //            }
      //            catch(InterruptedException e) {
      //                System.out.println("exception occurred");
      isupdated = helics.helicsInputIsUpdated(sub)
      if (isupdated == 1) {
        /* NOTE: The value sent by sender at time t is received by receiver at time t+deltat */
        value = helics.helicsInputGetDouble(sub)
        println(s"PI RECEIVER: Received value = $value at time $currenttime from PI SENDER")
      }
    }
    helics.helicsFederateFinalize(vFed)
    helics.helicsCloseLibrary
  }

}
