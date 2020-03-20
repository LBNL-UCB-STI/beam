package helics

import com.java.helics._
import com.java.helics.helicsJNI._
import org.matsim.api.core.v01.Coord

import scala.collection.mutable.ListBuffer

case class BeamFederate(name: String, bin: Int) {
  import BeamFederate._
  private var currenttime: Double = 0.0
  private val fedinfo = helics.helicsCreateFederateInfo
  helics.helicsFederateInfoSetCoreName(fedinfo, name)
  helics.helicsFederateInfoSetCoreTypeFromString(fedinfo, "zmq")
  helics.helicsFederateInfoSetCoreInitString(fedinfo, "--federates=1")
  helics.helicsFederateInfoSetTimeProperty(fedinfo, helics_property_time_delta_get(), 1.0)
  helics.helicsFederateInfoSetIntegerProperty(fedinfo, helics_property_int_log_level_get(), 1)
  private val cfed = helics.helicsCreateCombinationFederate(name, fedinfo)
  private val event = helics.helicsFederateRegisterPublication(cfed, "event", helics_data_type.helics_data_type_string, "")
  private val soc = helics.helicsFederateRegisterPublication(cfed, "soc", helics_data_type.helics_data_type_double, "")
  private val lat = helics.helicsFederateRegisterPublication(cfed, "lat", helics_data_type.helics_data_type_double, "")
  private val lng = helics.helicsFederateRegisterPublication(cfed, "lng", helics_data_type.helics_data_type_double, "")
  helics.helicsFederateEnterInitializingMode(cfed)
  helics.helicsFederateEnterExecutingMode(cfed)


  def publishSOC(time: Int, eventType: String, vehId: String, location: Coord, socInJoules: Double): Unit = {
    val vehEvent = s"$eventType:$vehId:$time"
    println(s"publishing: $vehEvent - $location - $socInJoules")
    helics.helicsPublicationPublishDouble(soc, socInJoules)
    helics.helicsPublicationPublishDouble(lat, location.getY)
    helics.helicsPublicationPublishDouble(lng, location.getX)
    helics.helicsPublicationPublishString(event, vehEvent)
  }

  def getCurrentTime(time: Int): Int = {
    while(currenttime < time)
      currenttime = helics.helicsFederateRequestTime(cfed, time)
    currenttime.toInt
  }



  def close(): Unit = {
    helics.helicsFederateFinalize(cfed)
    helics.helicsFederateFree(cfed)
    helics.helicsCloseLibrary()
  }
}

object BeamFederate {
  System.loadLibrary("JNIhelics")
  var outGoing: Option[BeamFederate] = None
  def getBeamFederate1(bin: Int): BeamFederate = {
    if (outGoing.isEmpty)
      outGoing = Some(BeamFederate("BeamFederate1", bin))
    outGoing.get
  }
}
