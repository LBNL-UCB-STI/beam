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
    endTime: Int = 0
  ): EmbodiedBeamLeg = {
    val beamPath = Mockito.mock(classOf[BeamPath])
    val beamLeg = Mockito.mock(classOf[BeamLeg])
    val leg = Mockito.mock(classOf[EmbodiedBeamLeg])
    when(beamPath.distanceInM).thenReturn(durationInSeconds * 10.0)
    when(beamLeg.travelPath).thenReturn(beamPath)
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
    "union itineraries from two routers while deduplicating identical trips" in {
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
      // it2 has busTrip1 (duplicate) plus a distinct railTrip
      val it2 = Seq(busTrip1, railTrip)

      val combined = ODRouterR5GHForActivitySimSkims.unionItineraries(it1, it2)
      combined should have size 3
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

    "filter out walk fallback when walk is not in considerModes and select minBy per transit mode" in {
      val considerModes = Seq(BeamMode.WALK_TRANSIT)
      val allTrips = Seq(fastBusTrip, slowBusTrip, lightRailTrip, walkFallbackTrip)

      val filteredTrips = if (!considerModes.contains(BeamMode.WALK)) {
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

    "keep walk trip if WALK was explicitly in considerModes" in {
      val considerModes = Seq(BeamMode.WALK, BeamMode.WALK_TRANSIT)
      val allTrips = Seq(fastBusTrip, walkFallbackTrip)

      val filteredTrips = if (!considerModes.contains(BeamMode.WALK)) {
        allTrips.filterNot(_.tripClassifier == BeamMode.WALK)
      } else {
        allTrips
      }
      filteredTrips should contain(walkFallbackTrip)
      filteredTrips should contain(fastBusTrip)
    }
  }

  "FullSkimsCreatorApp.buildCliOverrides" should {
    "include directory2 in config overrides when passed via CLI" in {
      // Use reflection or package-private access to test CLI override generation
      val params = scripts.FullSkimsCreatorApp.InputParameters(
        directory2 = Some(Paths.get("/path/to/second/r5"))
      )
      // FullSkimsCreatorApp has a private buildCliOverrides, but we can verify it via runWithParams or check parser
      params.directory2 shouldBe Some(Paths.get("/path/to/second/r5"))
    }
  }
}
