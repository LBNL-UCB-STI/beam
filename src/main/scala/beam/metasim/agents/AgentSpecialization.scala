package beam.metasim.agents

import org.matsim.api.core.v01.Coord

import scala.reflect.ClassTag

/**
  * Created by sfeygin on 2/22/17.
  */
object AgentSpecialization {

  trait MobileAgent {
    def getLocation: Coord

    def hasVehicleAvailable(vehicleType: ClassTag[_]): Boolean
  }

  trait PlanningAgent {

  }

}
