package beam.router.r5

import beam.agentsim.agents.vehicles.BeamVehicleType
import beam.agentsim.agents.vehicles.VehicleProtocol.StreetVehicle
import beam.agentsim.events.SpaceTime
import beam.router.BeamRouter.{EmbodyWithCurrentTravelTime, Location, RoutingRequest, RoutingResponse}
import beam.router.Modes
import beam.router.Modes.BeamMode
import beam.router.model.RoutingModel.TransitStopsInfo
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.sim.population.{AttributesOfIndividual, HouseholdAttributes}
import org.apache.avro.generic.GenericData
import org.matsim.api.core.v01.{Coord, Id}
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.collection.JavaConverters._

class RouteDumperTest extends AnyFunSuite with Matchers {
  test("Should be able to convert RoutingRequest to Record") {
    val origin = new Location(166027.034662, 2208.12088093)
    val time = 3000
    val destination = new Location(168255.58799, 2208.08034995)
    val streetVehicle = StreetVehicle(
      Id.createVehicleId("car"),
      Id.create("beamVilleCar", classOf[BeamVehicleType]),
      new SpaceTime(new Coord(origin.getX, origin.getY), time),
      Modes.BeamMode.CAR,
      asDriver = true,
      needsToCalculateCost = true
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
      requestId = 123,
      triggerId = 0
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

  test("Should be able to convert EmbodyWithCurrentTravelTime to Record") {
    val beamLeg = BeamLeg(
      0,
      BeamMode.CAR,
      0,
      BeamPath(
        linkIds = Vector(1, 2, 3, 4, 5),
        linkTravelTime = Vector(5, 5, 5, 5, 5),
        transitStops = Some(
          TransitStopsInfo(
            agencyId = "Agent",
            routeId = "RouteId",
            vehicleId = Id.createVehicleId("SomeVehicleId"),
            fromIdx = 11,
            toIdx = 42
          )
        ),
        startPoint = SpaceTime(x = 10, y = 20, time = 30),
        endPoint = SpaceTime(x = 40, y = 50, time = 50),
        distanceInM = 10.0
      )
    )
    val embodyWithCurrentTravelTime = EmbodyWithCurrentTravelTime(
      leg = beamLeg,
      vehicleId = Id.createVehicleId("car"),
      vehicleTypeId = Id.create("beamVilleCar", classOf[BeamVehicleType]),
      requestId = 123,
      triggerId = 0
    )

    val record = RouteDumper.toRecord(embodyWithCurrentTravelTime)
    record.get("requestId") shouldBe embodyWithCurrentTravelTime.requestId
    record.get("vehicleId") shouldBe embodyWithCurrentTravelTime.vehicleId.toString
    record.get("vehicleTypeId") shouldBe embodyWithCurrentTravelTime.vehicleTypeId.toString
    verifyBeamLeg(embodyWithCurrentTravelTime.leg, record)
  }

  test("Should be able to convert RoutingResponse to Record") {
    val routingResposne = RoutingResponse(
      itineraries = Vector(
        EmbodiedBeamTrip(
          legs = Vector(
            EmbodiedBeamLeg(
              beamLeg = BeamLeg(
                startTime = 28800,
                mode = BeamMode.WALK,
                duration = 50,
                travelPath = BeamPath(
                  linkIds = Vector(1, 2),
                  linkTravelTime = Vector(50, 50),
                  transitStops = None,
                  startPoint = SpaceTime(0.0, 0.0, 28800),
                  endPoint = SpaceTime(0.01, 0.0, 28850),
                  distanceInM = 1000d
                )
              ),
              beamVehicleId = Id.createVehicleId("body-dummyAgent"),
              Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
              asDriver = true,
              cost = 0.0,
              unbecomeDriverOnCompletion = false
            ),
            EmbodiedBeamLeg(
              beamLeg = BeamLeg(
                startTime = 28950,
                mode = BeamMode.CAR,
                duration = 50,
                travelPath = BeamPath(
                  linkIds = Vector(3, 4),
                  linkTravelTime = Vector(50, 50),
                  transitStops = None,
                  startPoint = SpaceTime(0.01, 0.0, 28950),
                  endPoint = SpaceTime(0.01, 0.01, 29000),
                  distanceInM = 1000d
                )
              ),
              beamVehicleId = Id.createVehicleId("car-1"),
              Id.create("TRANSIT-TYPE-DEFAULT", classOf[BeamVehicleType]),
              asDriver = true,
              cost = 0.0,
              unbecomeDriverOnCompletion = true
            )
          )
        )
      ),
      requestId = 123,
      request = None,
      isEmbodyWithCurrentTravelTime = false,
      triggerId = 0
    )

    val records = RouteDumper.toRecords(routingResposne)
    val legToIt = routingResposne.itineraries.zipWithIndex.flatMap { case (it, itIndex) =>
      it.beamLegs.zipWithIndex.map { case (leg, legIndex) =>
        ((leg, legIndex), (it, itIndex))
      }
    }
    records.asScala.zip(legToIt).foreach { case (record, ((leg, legIndex), (trip, tripIndex))) =>
      record.get("requestId") shouldBe routingResposne.requestId
      record.get("isEmbodyWithCurrentTravelTime") shouldBe routingResposne.isEmbodyWithCurrentTravelTime
      record.get("itineraryIndex") shouldBe tripIndex
      record.get("costEstimate") shouldBe trip.costEstimate
      record.get("tripClassifier") shouldBe trip.tripClassifier.value
      record.get("replanningPenalty") shouldBe trip.replanningPenalty
      record.get("totalTravelTimeInSecs") shouldBe trip.totalTravelTimeInSecs

      record.get("legIndex") shouldBe legIndex
      verifyBeamLeg(leg, record)
    }
  }

  private def verifyBeamLeg(leg: BeamLeg, record: GenericData.Record): Unit = {
    record.get("startTime") shouldBe leg.startTime
    record.get("mode") shouldBe leg.mode.value
    record.get("duration") shouldBe leg.duration
    record.get("linkIds").asInstanceOf[Array[Int]] shouldBe leg.travelPath.linkIds
    record.get("linkTravelTime").asInstanceOf[Array[Double]] shouldBe leg.travelPath.linkTravelTime
    leg.travelPath.transitStops.foreach { transitStops =>
      record.get("transitStops_agencyId") shouldBe transitStops.agencyId
      record.get("transitStops_routeId") shouldBe transitStops.routeId
      record.get("transitStops_vehicleId") shouldBe transitStops.vehicleId
      record.get("transitStops_fromIdx") shouldBe transitStops.fromIdx
      record.get("transitStops_toIdx") shouldBe transitStops.toIdx
    }
    record.get("startPoint_X") shouldBe leg.travelPath.startPoint.loc.getX
    record.get("startPoint_Y") shouldBe leg.travelPath.startPoint.loc.getY
    record.get("startPoint_time") shouldBe leg.travelPath.startPoint.time
    record.get("endPoint_X") shouldBe leg.travelPath.endPoint.loc.getX
    record.get("endPoint_Y") shouldBe leg.travelPath.endPoint.loc.getY
    record.get("endPoint_time") shouldBe leg.travelPath.endPoint.time
    record.get("distanceInM") shouldBe leg.travelPath.distanceInM
  }
}
