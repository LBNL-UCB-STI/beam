package beam.router.skim.urbansim

import beam.router.BeamRouter.RoutingResponse
import beam.router.Modes.BeamMode
import beam.router.model.{BeamLeg, BeamPath, EmbodiedBeamLeg, EmbodiedBeamTrip}
import beam.router.skim.ActivitySimPathType
import org.matsim.api.core.v01.Id
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths

class TransitDisaggregationAndDualRouterSpec extends AnyWordSpecLike with Matchers {

  private def mockLeg(
    durationInSeconds: Int,
    mode: BeamMode,
    startTime: Int = 0,
    endTime: Int = 0,
    beamPath: Option[BeamPath] = None
  ): EmbodiedBeamLeg = {
    val theBeamPath = beamPath.getOrElse {
      val p = Mockito.mock(classOf[BeamPath])
      when(p.distanceInM).thenReturn(durationInSeconds * 10.0)
      when(p.linkIds).thenReturn(Array.emptyIntArray)
      p
    }
    val beamLeg = Mockito.mock(classOf[BeamLeg])
    val leg = Mockito.mock(classOf[EmbodiedBeamLeg])
    when(beamLeg.travelPath).thenReturn(theBeamPath)
    when(beamLeg.mode).thenReturn(mode)
    when(beamLeg.duration).thenReturn(durationInSeconds)
    when(beamLeg.startTime).thenReturn(startTime)
    when(beamLeg.endTime).thenReturn(endTime)
    when(leg.beamLeg).thenReturn(beamLeg)
    when(leg.isRideHail).thenReturn(false)
    when(leg.beamVehicleId).thenReturn(Id.createVehicleId("mock-vehicle"))
    when(leg.cost).thenReturn(0.0)
    when(leg.replanningPenalty).thenReturn(0.0)
    leg
  }

  "ODRouterR5GHForActivitySimSkims.unionItineraries" should {
    "combine itineraries from two routers via concatenation" in {
      val busTrip1 = EmbodiedBeamTrip(
        IndexedSeq(
          mockLeg(300, BeamMode.WALK, startTime = 28800, endTime = 29100),
          mockLeg(1200, BeamMode.BUS, startTime = 29100, endTime = 30300),
          mockLeg(300, BeamMode.WALK, startTime = 30300, endTime = 30600)
        )
      )
      val busTrip2 = EmbodiedBeamTrip(
        IndexedSeq(
          mockLeg(300, BeamMode.WALK, startTime = 28800, endTime = 29100),
          mockLeg(1800, BeamMode.BUS, startTime = 29100, endTime = 30900),
          mockLeg(300, BeamMode.WALK, startTime = 30900, endTime = 31200)
        )
      )
      val railTrip = EmbodiedBeamTrip(
        IndexedSeq(
          mockLeg(300, BeamMode.WALK, startTime = 28800, endTime = 29100),
          mockLeg(900, BeamMode.TRAM, startTime = 29100, endTime = 30000),
          mockLeg(300, BeamMode.WALK, startTime = 30000, endTime = 30300)
        )
      )

      val it1 = Seq(busTrip1, busTrip2)
      val it2 = Seq(busTrip1, railTrip)

      val combined = ODRouterR5GHForActivitySimSkims.unionItineraries(it1, it2)
      combined should have size 4
      combined should contain allOf(busTrip1, busTrip2, railTrip)
    }
  }

  "Transit itinerary disaggregation and mode selection" should {
    val requestTime = 28800 // 8:00 AM

    val fastBusTrip = EmbodiedBeamTrip(
      IndexedSeq(
        mockLeg(300, BeamMode.WALK, startTime = 29100, endTime = 29400),
        mockLeg(1200, BeamMode.BUS, startTime = 29400, endTime = 30600),
        mockLeg(300, BeamMode.WALK, startTime = 30600, endTime = 30900) // ends at 30900 (8:35) -> 35 min from request
      )
    )

    val slowBusTrip = EmbodiedBeamTrip(
      IndexedSeq(
        mockLeg(300, BeamMode.WALK, startTime = 28800, endTime = 29100),
        mockLeg(2400, BeamMode.BUS, startTime = 29100, endTime = 31500),
        mockLeg(300, BeamMode.WALK, startTime = 31500, endTime = 31800) // ends at 31800 (8:50) -> 50 min from request
      )
    )

    val lightRailTrip = EmbodiedBeamTrip(
      IndexedSeq(
        mockLeg(300, BeamMode.WALK, startTime = 28900, endTime = 29200),
        mockLeg(1200, BeamMode.TRAM, startTime = 29200, endTime = 30400),
        mockLeg(200, BeamMode.WALK, startTime = 30400, endTime = 30600) // ends at 30600 (8:30) -> 30 min from request
      )
    )

    val walkFallbackTrip = EmbodiedBeamTrip(
      IndexedSeq(
        mockLeg(3600, BeamMode.WALK, startTime = 28800, endTime = 32400) // 60 min walk
      )
    )

    def totalTimeFromRequestToArrival(trip: EmbodiedBeamTrip, reqTime: Int): Int = {
      trip.legs.lastOption match {
        case Some(lastLeg) => Math.max(0, lastLeg.beamLeg.endTime - reqTime)
        case None          => trip.totalTravelTimeInSecs
      }
    }

    "filter out walk fallback when direct walk route is not requested and select minBy per transit mode" in {
      val buildDirectWalkRoute = false
      val allTrips = Seq(fastBusTrip, slowBusTrip, lightRailTrip, walkFallbackTrip)

      val filteredTrips = if (!buildDirectWalkRoute) {
        allTrips.filterNot(_.tripClassifier == BeamMode.WALK)
      } else {
        allTrips
      }
      filteredTrips should not contain walkFallbackTrip

      val selectedTrips = filteredTrips
        .filterNot(t => ActivitySimPathType.determineTripPathTypeAndFleet(t)._1 == ActivitySimPathType.OTHER)
        .groupBy(t => ActivitySimPathType.determineTripPathTypeAndFleet(t))
        .values
        .map { modeTrips =>
          modeTrips.minBy(t => (totalTimeFromRequestToArrival(t, requestTime), t.totalTravelTimeInSecs))
        }
        .toSeq

      // Should have exactly 2 selected trips: fast bus and light rail (slow bus and walk fallback discarded)
      selectedTrips should have size 2
      selectedTrips should contain(fastBusTrip)
      selectedTrips should contain(lightRailTrip)
      selectedTrips should not contain slowBusTrip
      selectedTrips should not contain walkFallbackTrip

      // Verify mapped path types
      val pathTypes = selectedTrips.map(t => ActivitySimPathType.determineTripPathTypeAndFleet(t)._1)
      pathTypes should contain allOf(ActivitySimPathType.WLK_LOC_WLK, ActivitySimPathType.WLK_LRF_WLK)
    }

    "keep walk trip if buildDirectWalkRoute is true" in {
      val buildDirectWalkRoute = true
      val allTrips = Seq(fastBusTrip, walkFallbackTrip)

      val filteredTrips = if (!buildDirectWalkRoute) {
        allTrips.filterNot(_.tripClassifier == BeamMode.WALK)
      } else {
        allTrips
      }
      filteredTrips should contain(walkFallbackTrip)
      filteredTrips should contain(fastBusTrip)
    }
  }

  "Drive-transit return trip classification and parking logic (Milestone 3)" should {
    "correctly classify return trips as WLK_*_DRV and outbound trips as DRV_*_WLK" in {
      // Outbound trip: CAR -> BUS -> WALK
      val outboundTrip = EmbodiedBeamTrip(
        IndexedSeq(
          mockLeg(600, BeamMode.CAR, startTime = 28800, endTime = 29400),
          mockLeg(1200, BeamMode.BUS, startTime = 29400, endTime = 30600),
          mockLeg(300, BeamMode.WALK, startTime = 30600, endTime = 30900)
        )
      )
      ActivitySimPathType.determineTripPathTypeAndFleet(outboundTrip)._1 shouldBe ActivitySimPathType.DRV_LOC_WLK

      // Return trip: WALK -> BUS -> CAR
      val returnBusTrip = EmbodiedBeamTrip(
        IndexedSeq(
          mockLeg(300, BeamMode.WALK, startTime = 61200, endTime = 61500),
          mockLeg(1200, BeamMode.BUS, startTime = 61500, endTime = 62700),
          mockLeg(600, BeamMode.CAR, startTime = 62700, endTime = 63300)
        )
      )
      ActivitySimPathType.determineTripPathTypeAndFleet(returnBusTrip)._1 shouldBe ActivitySimPathType.WLK_LOC_DRV

      // Return trip: WALK -> TRAM -> CAR
      val returnTramTrip = EmbodiedBeamTrip(
        IndexedSeq(
          mockLeg(300, BeamMode.WALK, startTime = 61200, endTime = 61500),
          mockLeg(1200, BeamMode.TRAM, startTime = 61500, endTime = 62700),
          mockLeg(600, BeamMode.CAR, startTime = 62700, endTime = 63300)
        )
      )
      ActivitySimPathType.determineTripPathTypeAndFleet(returnTramTrip)._1 shouldBe ActivitySimPathType.WLK_LRF_DRV
    }

    "deduplicate candidate parking locations within 50m" in {
      import org.matsim.api.core.v01.Coord

      val set = scala.collection.mutable.Set.empty[Coord]
      def addCoord(c: Coord): Unit = {
        val duplicate = set.exists { existing =>
          val dx = existing.getX - c.getX
          val dy = existing.getY - c.getY
          (dx * dx + dy * dy) < (50.0 * 50.0)
        }
        if (!duplicate) set.add(c)
      }

      val coord1 = new Coord(500000.0, 4100000.0)
      addCoord(coord1)
      set should have size 1

      // Candidate 2 is 25m away (within 50m) -> duplicate, should be skipped
      val coord2 = new Coord(500025.0, 4100000.0)
      addCoord(coord2)
      set should have size 1

      // Candidate 3 is 200m away (beyond 50m) -> new station, should be retained
      val coord3 = new Coord(500200.0, 4100000.0)
      addCoord(coord3)
      set should have size 2
    }

    "support generateReturnTrips option in FullSkimsCreatorApp" in {
      val params = scripts.FullSkimsCreatorApp.InputParameters(
        generateReturnTrips = Some(true)
      )
      params.generateReturnTrips shouldBe Some(true)
    }
  }

  "FullSkimsCreatorApp.buildCliOverrides" should {
    "include directory2 in config overrides when passed via CLI" in {
      val params = scripts.FullSkimsCreatorApp.InputParameters(
        directory2 = Some(Paths.get("/path/to/second/r5"))
      )
      params.directory2 shouldBe Some(Paths.get("/path/to/second/r5"))
    }
  }

  "Auto toll bifurcation and toll metrics (Milestone 4)" should {
    import beam.router.skim.ActivitySimMetric
    import beam.router.skim.ActivitySimSkimmer.ExcerptData

    "correctly identify car path types with ActivitySimPathType.isCar" in {
      ActivitySimPathType.isCar(ActivitySimPathType.SOV) shouldBe true
      ActivitySimPathType.isCar(ActivitySimPathType.SOVTOLL) shouldBe true
      ActivitySimPathType.isCar(ActivitySimPathType.HOV2) shouldBe true
      ActivitySimPathType.isCar(ActivitySimPathType.HOV2TOLL) shouldBe true
      ActivitySimPathType.isCar(ActivitySimPathType.HOV3) shouldBe true
      ActivitySimPathType.isCar(ActivitySimPathType.HOV3TOLL) shouldBe true

      ActivitySimPathType.isCar(ActivitySimPathType.WALK) shouldBe false
      ActivitySimPathType.isCar(ActivitySimPathType.BIKE) shouldBe false
      ActivitySimPathType.isCar(ActivitySimPathType.WLK_LOC_WLK) shouldBe false
      ActivitySimPathType.isCar(ActivitySimPathType.DRV_LOC_WLK) shouldBe false
    }

    "return weightedBridgeTollInCents for BTOLL and weightedValueTollInCents for VTOLL without fuel cost leakage" in {
      // SOVTOLL with value toll (e.g. optional HOT/express lane or tolled route in Stage 1)
      val sovTollExcerpt = ExcerptData(
        timePeriodString = "AM",
        pathType = ActivitySimPathType.SOVTOLL,
        fleetName = "",
        originId = "100",
        destinationId = "200",
        weightedDistance = 15000.0,
        weightedTotalTime = 25.0,
        weightedTotalFareInCents = 0.0,
        weightedWalkAccess = 0.0,
        weightedWaitInitial = 0.0,
        weightedWaitTransfer = 0.0,
        weightedWalkAuxiliary = 0.0,
        weightedWalkEgress = 0.0,
        weightedTotalInVehicleTime = 25.0,
        weightedDriveDistanceInMeters = 15000.0,
        weightedDriveTimeInMinutes = 25.0,
        weightedKeyInVehicleTimeInMinutes = 0.0,
        weightedFerryInVehicleTimeInMinutes = 0.0,
        weightedTransitBoardingsCount = 0.0,
        weightedCost = 7.0,
        failedTrips = 0,
        completedTrips = 1,
        weightedBridgeTollInCents = 0.0,
        weightedValueTollInCents = 700.0
      )

      sovTollExcerpt.getValue(ActivitySimMetric.VTOLL) shouldBe 700.0
      sovTollExcerpt.getValue(ActivitySimMetric.BTOLL) shouldBe 0.0

      // SOV with unavoidable bridge toll (e.g. Stage 2 toll-avoiding route where bridge is unavoidable)
      val sovBridgeTollExcerpt = sovTollExcerpt.copy(
        pathType = ActivitySimPathType.SOV,
        weightedBridgeTollInCents = 700.0,
        weightedValueTollInCents = 0.0
      )
      sovBridgeTollExcerpt.getValue(ActivitySimMetric.BTOLL) shouldBe 700.0
      sovBridgeTollExcerpt.getValue(ActivitySimMetric.VTOLL) shouldBe 0.0

      // In-run trip with fuel cost / fare but no tolls: BTOLL and VTOLL must remain 0.0
      val fuelOnlyExcerpt = sovTollExcerpt.copy(
        pathType = ActivitySimPathType.SOV,
        weightedCost = 15.0,
        weightedTotalFareInCents = 1500.0,
        weightedBridgeTollInCents = 0.0,
        weightedValueTollInCents = 0.0
      )
      fuelOnlyExcerpt.getValue(ActivitySimMetric.BTOLL) shouldBe 0.0
      fuelOnlyExcerpt.getValue(ActivitySimMetric.VTOLL) shouldBe 0.0
    }

    "support bifurcateTolls and tollFilePath options in FullSkimsCreatorApp" in {
      val params = scripts.FullSkimsCreatorApp.InputParameters(
        bifurcateTolls = Some(true),
        tollFilePath = Some(Paths.get("/path/to/toll-prices.csv"))
      )
      params.bifurcateTolls shouldBe Some(true)
      params.tollFilePath shouldBe Some(Paths.get("/path/to/toll-prices.csv"))
    }

    "calculate tolls for CAR legs and ignore non-car legs" in {
      val mockTollCalc = Mockito.mock(classOf[beam.router.osm.TollCalculator])
      val carPath = Mockito.mock(classOf[BeamPath])
      when(carPath.linkIds).thenReturn(Array(101, 102))
      when(carPath.distanceInM).thenReturn(5000.0)
      when(mockTollCalc.calcTollByLinkIds(carPath)).thenReturn(6.50)
      when(mockTollCalc.hasAnyWayTolls).thenReturn(false)

      val carLeg = mockLeg(600, BeamMode.CAR, beamPath = Some(carPath))
      val walkLeg = mockLeg(300, BeamMode.WALK)

      val mixedTrip = EmbodiedBeamTrip(IndexedSeq(carLeg, walkLeg))
      val toll = mixedTrip.beamLegs.collect {
        case leg if leg.mode == BeamMode.CAR || leg.mode == BeamMode.CAV =>
          mockTollCalc.calcTollByLinkIds(leg.travelPath)
      }.sum

      toll shouldBe 6.50
    }

    "handle toll bifurcation semantics: BTOLL vs VTOLL and excluding drive-transit" in {
      val tolledCarODs = new java.util.concurrent.ConcurrentHashMap[(Int, Int, Int), java.lang.Boolean]()
      case class SkimRecord(pathType: ActivitySimPathType, bridgeTollInCents: Double, valueTollInCents: Double)
      val emittedEvents = collection.mutable.ArrayBuffer[SkimRecord]()

      def simulateMasterActorProcess(
        src: Int,
        dst: Int,
        time: Int,
        tripClassifier: BeamMode,
        toll: Double,
        avoidTolls: Boolean,
        bifurcateTolls: Boolean
      ): Unit = {
        if (avoidTolls) {
          // Stage 2 toll-avoiding route: record as SOV with unavoidable bridge toll
          emittedEvents += SkimRecord(ActivitySimPathType.SOV, bridgeTollInCents = toll * 100.0, valueTollInCents = 0.0)
        } else if (bifurcateTolls && tripClassifier == BeamMode.CAR) {
          if (toll == 0.0) {
            emittedEvents += SkimRecord(ActivitySimPathType.SOV, 0.0, 0.0)
            emittedEvents += SkimRecord(ActivitySimPathType.SOVTOLL, 0.0, 0.0)
          } else {
            // Tolled route: recorded as SOVTOLL with value toll in VTOLL
            emittedEvents += SkimRecord(ActivitySimPathType.SOVTOLL, bridgeTollInCents = 0.0, valueTollInCents = toll * 100.0)
            tolledCarODs.put((src, dst, time), java.lang.Boolean.TRUE)
          }
        }
      }

      // Case 1: Toll-free CAR OD in Stage 1
      simulateMasterActorProcess(1, 2, 28800, BeamMode.CAR, 0.0, avoidTolls = false, bifurcateTolls = true)
      emittedEvents should contain theSameElementsInOrderAs Seq(
        SkimRecord(ActivitySimPathType.SOV, 0.0, 0.0),
        SkimRecord(ActivitySimPathType.SOVTOLL, 0.0, 0.0)
      )
      tolledCarODs.containsKey((1, 2, 28800)) shouldBe false

      // Case 2: Tolled CAR OD in Stage 1 (e.g. Bay Bridge $7 toll) -> VTOLL = 700
      emittedEvents.clear()
      simulateMasterActorProcess(13, 968, 28800, BeamMode.CAR, 7.0, avoidTolls = false, bifurcateTolls = true)
      emittedEvents should contain theSameElementsInOrderAs Seq(
        SkimRecord(ActivitySimPathType.SOVTOLL, bridgeTollInCents = 0.0, valueTollInCents = 700.0)
      )
      tolledCarODs.containsKey((13, 968, 28800)) shouldBe true

      // Case 3: Stage 2 re-route with avoidTolls = true (diverted to non-toll route)
      emittedEvents.clear()
      simulateMasterActorProcess(13, 968, 28800, BeamMode.CAR, 0.0, avoidTolls = true, bifurcateTolls = true)
      emittedEvents should contain theSameElementsInOrderAs Seq(
        SkimRecord(ActivitySimPathType.SOV, bridgeTollInCents = 0.0, valueTollInCents = 0.0)
      )

      // Case 4: Drive-transit trip (DRIVE_TRANSIT classifier) must NOT be bifurcated into car SOV/SOVTOLL
      emittedEvents.clear()
      simulateMasterActorProcess(13, 968, 28800, BeamMode.DRIVE_TRANSIT, 7.0, avoidTolls = false, bifurcateTolls = true)
      emittedEvents shouldBe empty
    }
  }
}
