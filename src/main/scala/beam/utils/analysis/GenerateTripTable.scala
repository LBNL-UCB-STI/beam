package beam.utils.analysis

import java.io.Closeable

import beam.utils.EventReader
import beam.utils.csv.CsvWriter
import org.matsim.api.core.v01.events.Event
import org.matsim.core.api.experimental.events.EventsManager
import org.matsim.core.events.EventsManagerImpl
import org.matsim.core.events.handler.BasicEventHandler
import org.matsim.core.network.NetworkUtils
import org.matsim.core.network.io.MatsimNetworkReader

import scala.io.Source

object GenerateTripTable {



  //

  //
  def main(args: Array[String]): Unit = {



    val network = NetworkUtils.createNetwork()
    new MatsimNetworkReader(network)
      .readFile("/outputNetwork.xml.gz")


    val (events: Iterator[Event], closable: Closeable) = EventReader.fromCsvFile("/beamville__2019-09-02_21-54-09/ITERS/it.0/0.events.csv", filter )


    val csvWriter =
      new CsvWriter("", Vector("vehicleId", "time", "startX", "startY", "endX", "endY", "numberOfPassengers"))

    events.foreach{
      event =>


        if (event.getEventType.equalsIgnoreCase("actend")){

        }



    }
  }

  def filter(event: Event): Boolean = {
    //val attribs = event.getAttributes
    // We need only PathTraversal with mode `CAR`, no ride hail
    //val isNeededEvent = event.getEventType == "PathTraversal" && Option(attribs.get("mode")).contains("car") &&
    //  !Option(attribs.get("vehicle")).exists(vehicle => vehicle.contains("rideHailVehicle-"))
    //isNeededEvent
    true
  }

}
