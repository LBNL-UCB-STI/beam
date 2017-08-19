package beam.playground.dserdyuk

import akka.actor.{ActorSystem, Props}
import beam.agentsim.agents.vehicles._
import beam.agentsim.agents.vehicles.household.HouseholdActor
import beam.sim.config.ConfigModule
import org.matsim.households.{HouseholdsImpl, HouseholdsReaderV10}
import org.matsim.vehicles.{VehicleReaderV1, VehicleUtils}

import scala.collection.JavaConverters._

/**
  * @author dserdiuk on 6/18/17.
  */
object VehicleLoader extends App {

  val config  = ConfigModule.matSimConfig

  val scenario = config.scenario()
  val vehicles = VehicleUtils.createVehiclesContainer()
  val reader  = new VehicleReaderV1(vehicles)
  val vehiclePath = getClass.getResource("/vehicles.xml").getFile
  reader.readFile(vehiclePath)
  Console.print(s"Loaded ${vehicles.getVehicles.size()} vehicles and ${vehicles.getVehicleTypes} vehicle types")

//  val actorSystem = ActorSystem()
//  val vehicleActors  = vehicles.getVehicles.asScala.map { case (vehicleId,matSimVehicle) =>
//      val beamVehicle  =  actorSystem.actorOf(Props(classOf[BeamVehicle],  vehicleId,
//        VehicleData.vehicle2vehicleData(matSimVehicle),
//        new Powertrain(BeamVehicle.energyPerUnitByType(matSimVehicle.getType.getId)),
//        //TODO: trajectory looks irrelevant here,
//        //we need to have person/owner of vehicle to build trajectory from activity plan, right ?
//        new Trajectory(), None, Nil, None
//      ), BeamVehicle.buildActorName(vehicleId))
//    (vehicleId, beamVehicle)
//  }.toMap

//  private val households = new HouseholdsImpl()
//  val householdsReader = new  HouseholdsReaderV10(households)
//  householdsReader.readFile(getClass.getResource("/households.xml").getFile)
//  //TODO: we may move creation of vehicle agents in Household context for better life-circle management
//  val householdActors = households.getHouseholds.asScala.map { case (householdId, matSimHousehold) =>
//    val houseHoldVehicles = matSimHousehold.getVehicleIds.asScala.map{ id => (id, vehicleActors(id))}.toMap
//    val householdActor = actorSystem.actorOf(Props(classOf[HouseholdActor],
//      householdId, matSimHousehold, houseHoldVehicles))
//      householdActor
//  }
}


