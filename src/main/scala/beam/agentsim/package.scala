package beam

import beam.agentsim.agents.PersonAgent
import beam.agentsim.agents.ridehail.RideHailAgent
import beam.agentsim.agents.vehicles.EnergyEconomyAttributes.Powertrain
import beam.agentsim.agents.vehicles.{BeamVehicle, BeamVehicleType}
import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle

import scala.collection.JavaConverters
import scala.language.implicitConversions

/**
  * Created by sfeygin on 1/27/17.
  */
package object agentsim {

  implicit def personId2PersonAgentId(id: Id[Person]): Id[PersonAgent] =
    Id.create(id, classOf[PersonAgent])

  implicit def personAgentId2PersonId(id: Id[PersonAgent]): Id[Person] = Id.createPersonId(id)

  implicit def vehicleId2BeamVehicleId(id: Id[Vehicle]): Id[BeamVehicle] =
    Id.create(id, classOf[BeamVehicle])

  implicit def beamVehicleId2VehicleId(id: Id[BeamVehicle]): Id[Vehicle] = Id.createVehicleId(id)

  implicit def beamVehicleMaptoMatsimVehicleMap(
    beamVehicleMap: Map[Id[BeamVehicle], BeamVehicle]
  ): Map[Id[Vehicle], Vehicle] = {
    beamVehicleMap.map({ case (vid, veh) => (Id.createVehicleId(vid), veh.matSimVehicle) })
  }

  //TODO: Make this work for modes other than car
  implicit def matsimVehicleMap2BeamVehicleMap(
    matsimVehicleMap: java.util.Map[Id[Vehicle], Vehicle]
  ): Map[Id[BeamVehicle], BeamVehicle] = {
    JavaConverters
      .mapAsScalaMap(matsimVehicleMap)
      .map({
        case (vid, veh) =>
          (
            Id.create(vid, classOf[BeamVehicle]),
            new BeamVehicle(
              Powertrain
                .PowertrainFromMilesPerGallon(veh.getType.getEngineInformation.getGasConsumption),
              veh,
              None,
              BeamVehicleType.CarVehicle,
              None,
              None
            )
          )
      })
      .toMap
  }

  implicit def personId2RideHailAgentId(id: Id[Person]): Id[RideHailAgent] = {
    Id.create(s"${RideHailAgent.idPrefix}${prefixStrip(id)}", classOf[RideHailAgent])
  }

  def prefixStrip(id: Id[_]): String = {
    id.toString.replaceFirst("(?!=-)[a-zA-Z]+", "")
  }
}
