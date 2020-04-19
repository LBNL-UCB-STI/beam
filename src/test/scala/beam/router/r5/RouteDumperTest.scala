package beam.router.r5

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{Location, RoutingRequest}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import org.apache.avro.generic.GenericData
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.{FunSuite, Matchers}

class RouteDumperTest extends FunSuite with Matchers {
  test("Should be able to convert RoutingRequest to Record") {
    val origin = new Location(166027.034662, 2208.12088093)
    val time = 3000
    val destination = new Location(168255.58799, 2208.08034995)
    val streetVehicle = StreetVehicle(
      Id.createVehicleId("car"),
      Id.create("beamVilleCar", classOf[BeamVehicleType]),
      new SpaceTime(new Coord(origin.getX, origin.getY), time),
      Modes.BeamMode.CAR,
      asDriver = true
    )
    val attributesOfIndividual = AttributesOfIndividual(
      HouseholdAttributes("1", 200, 300, 400, 500),
      None,
      true,
      Vector(BeamMode.CAR),
      valueOfTime = 10000000.0,
      Some(42),
      Some(1234)
    )
    val request = RoutingRequest(
      originUTM = origin,
      destinationUTM = destination,
      departureTime = time,
      withTransit = false,
      streetVehicles = Vector(streetVehicle),
      attributesOfIndividual = Some(attributesOfIndividual),
      requestId = 123
    )
    val record = RouteDumper.toRecord(request)
    record.get("requestId") shouldBe 123
    record.get("originUTM_X") shouldBe origin.getX
    record.get("originUTM_Y") shouldBe origin.getY
    record.get("destinationUTM_X") shouldBe destination.getX
    record.get("destinationUTM_Y") shouldBe destination.getY
    record.get("departureTime") shouldBe time
    record.get("withTransit") shouldBe false
    record.get("requestId") shouldBe 123

    // Verify StreetVehicles
    val readSvs = record.get("streetVehicles").asInstanceOf[GenericData.Array[Any]]
    readSvs.size() shouldBe 1
    val readStreetVehicle = readSvs.get(0).asInstanceOf[GenericData.Record]
    readStreetVehicle.get("id") shouldBe streetVehicle.id.toString
    readStreetVehicle.get("vehicleTypeId") shouldBe streetVehicle.vehicleTypeId.toString
    readStreetVehicle.get("locationUTM_X") shouldBe streetVehicle.locationUTM.loc.getX
    readStreetVehicle.get("locationUTM_Y") shouldBe streetVehicle.locationUTM.loc.getY
    readStreetVehicle.get("locationUTM_time") shouldBe streetVehicle.locationUTM.time
    readStreetVehicle.get("mode") shouldBe streetVehicle.mode.value
    readStreetVehicle.get("asDriver") shouldBe streetVehicle.asDriver

    // Verify AttributesOfIndividual
    val readAttributesOfIndividual = record.get("attributesOfIndividual").asInstanceOf[GenericData.Record]
    val readHouseholdAttributes = readAttributesOfIndividual.get("householdAttributes").asInstanceOf[GenericData.Record]
    readHouseholdAttributes.get("householdId") shouldBe attributesOfIndividual.householdAttributes.householdId
    readHouseholdAttributes.get("householdIncome") shouldBe attributesOfIndividual.householdAttributes.householdIncome
    readHouseholdAttributes.get("householdSize") shouldBe attributesOfIndividual.householdAttributes.householdSize
    readHouseholdAttributes.get("numCars") shouldBe attributesOfIndividual.householdAttributes.numCars
    readHouseholdAttributes.get("numBikes") shouldBe attributesOfIndividual.householdAttributes.numBikes

    readAttributesOfIndividual.get("modalityStyle") shouldBe null
    readAttributesOfIndividual.get("isMale") shouldBe attributesOfIndividual.isMale
    readAttributesOfIndividual.get("availableModes") shouldBe attributesOfIndividual.availableModes
      .map(_.value)
      .mkString(" ")
    readAttributesOfIndividual.get("valueOfTime") shouldBe attributesOfIndividual.valueOfTime
    readAttributesOfIndividual.get("age") shouldBe attributesOfIndividual.age.get
    readAttributesOfIndividual.get("income") shouldBe attributesOfIndividual.income.get
  }
}
