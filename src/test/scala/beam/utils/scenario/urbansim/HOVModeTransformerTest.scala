package beam.utils.scenario.urbansim

import beam.router.Modes.BeamMode
import beam.router.Modes.BeamMode._
import beam.utils.scenario._
import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

import scala.util.Random

class HOVModeTransformerTest extends AnyFunSuite with Matchers {

  import HOVModeTransformerTest._

  test("only 'HOV' legs should be affected") {
    val modes = BeamMode.allModes ++ Seq(HOV2_TELEPORTATION, HOV3_TELEPORTATION, CAR_HOV2, CAR_HOV3)
    val plansBefore = modes.zipWithIndex.flatMap { case (mode, idx) =>
      newTrip(personId = idx, 1, mode.value)
    }
    val persons = Seq(newPerson(1, 1))
    val households = Seq(newHousehold(1, 1))

    val plansAfter = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plansBefore, persons, households)
    plansAfter should equal(plansBefore)
  }

  test("'HOV' legs should not be in the result") {
    val allHov = Set("HOV2", "HOV3", "hov2", "hov3")
    allHov.foreach { mode =>
      val plansBefore = newTrip(1, 1, mode)
      val persons = Seq(newPerson(1, 1))
      val households = Seq(newHousehold(1, 1))

      val plansAfter = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plansBefore, persons, households)
      plansAfter.foreach { plan =>
        plan.legMode match {
          case Some(mode) => allHov shouldNot contain(mode)
          case _          =>
        }
      }
    }
  }

  test("'HOV2' legs should be transformed only into car_hov2|hov2_teleportation") {
    val expected = Set(CAR_HOV2.value, HOV2_TELEPORTATION.value)
    val allHov = Set("HOV2", "hov2")
    allHov.foreach { mode =>
      val plansBefore = newTrip(1, 1, mode)
      val persons = Seq(newPerson(1, 1))
      val households = Seq(newHousehold(1, 1))

      val plansAfter = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plansBefore, persons, households)
      plansAfter.foreach { plan =>
        plan.legMode match {
          case Some(mode) => expected should contain(mode)
          case _          =>
        }
      }
    }
  }

  test("'HOV3' legs should be transformed only into car_hov3|hov3_teleportation") {
    val expected = Set(CAR_HOV3.value, HOV3_TELEPORTATION.value)
    val allHov = Set("HOV3", "hov3")
    allHov.foreach { mode =>
      val plansBefore = newTrip(1, 1, mode)
      val persons = Seq(newPerson(1, 1))
      val households = Seq(newHousehold(1, 1))

      val plansAfter = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plansBefore, persons, households)
      plansAfter.foreach { plan =>
        plan.legMode match {
          case Some(mode) => expected should contain(mode)
          case _          =>
        }
      }
    }
  }

  private def getNumberOfHOVTrips(numberOfTrips: Int, maybePersonId: Option[Int] = None) = {
    val allHov = Set("HOV2", "HOV3", "hov2", "hov3")
    val modes = (1 to (numberOfTrips / 4 + 3)).flatMap(_ => allHov).take(numberOfTrips)
    maybePersonId match {
      case Some(personId) => modes.flatMap(mode => newTrip(personId, 1, mode))
      case None           => modes.zipWithIndex.flatMap { case (mode, personId) => newTrip(personId, 1, mode) }
    }
  }

  test("'HOV2' or 'HOV3' legs should be transformed into teleportation if 0 car available") {
    val plansBefore = getNumberOfHOVTrips(50, Some(1))
    val persons = Seq(newPerson(1, 1))
    val households = Seq(newHousehold(1, 0))

    val plansAfter = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plansBefore, persons, households)
    val modesAfter = plansAfter.filter(plan => plan.legMode.nonEmpty).map(_.legMode.get.toLowerCase).toSet

    Seq("hov2", "hov3", CAR_HOV2.value, CAR_HOV3.value)
      .map(_.toLowerCase)
      .foreach { mode =>
        modesAfter shouldNot contain(mode)
      }

    Seq(HOV2_TELEPORTATION, HOV3_TELEPORTATION)
      .map(_.value.toLowerCase)
      .foreach { mode =>
        modesAfter should contain(mode)
      }
  }

  test("hov2 / hov3 legs if car available should be transformed into car_hov / hov_teleportation") {
    val plansBefore = getNumberOfHOVTrips(302)
    val persons = Seq(newPerson(1, 1))
    val households = Seq(newHousehold(1, 1))

    val plansAfter = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plansBefore, persons, households)
    val modesAfter = plansAfter.filter(plan => plan.legMode.nonEmpty).map(_.legMode.get.toLowerCase).toSet

    Seq("hov2", "hov3").foreach { mode =>
      modesAfter shouldNot contain(mode)
    }

    Seq(CAR_HOV2, CAR_HOV3, HOV2_TELEPORTATION, HOV3_TELEPORTATION)
      .map(_.value.toLowerCase)
      .foreach { mode =>
        modesAfter should contain(mode)
      }
  }

  test("separation into trips should work") {
    val plansBefore = getNumberOfHOVTrips(100)
    val trips = HOVModeTransformer.splitToTrips(plansBefore)
    trips.size shouldBe 100
  }

  test("separation into trips should work for complicated trips") {
    val inputPlans = newTrip(
      1,
      1,
      modes = Seq("HOV2", "HOV2", "HOV2", "WALK", "WALK", "CAR", "CAR"),
      activities = Seq(
        Act("Home", 1.1, 1.1),
        Act("Shopping"),
        Act("Other"),
        Act("Work"),
        Act("Meal"),
        Act("Work"),
        Act("Shopping"),
        Act("Home", 1.1, 1.1)
      )
    ) ++ newTrip(
      1,
      1,
      modes = Seq("HOV2", "HOV2", "HOV2", "CAR", "CAR"),
      activities =
        Seq(Act("Home", 1.1, 1.1), Act("Shopping"), Act("Other"), Act("Work"), Act("Shopping"), Act("Home", 1.1, 1.1))
    ) ++ newTrip(
      1,
      1,
      modes = Seq("CAR", "CAR"),
      activities = Seq(Act("Home", 1.1, 1.1), Act("Work"), Act("Home", 1.1, 1.1))
    ) ++ newTrip(
      1,
      1,
      modes = Seq(WALK_TRANSIT.value, WALK.value, WALK.value),
      activities = Seq(Act("Home", 1.1, 1.1), Act("Work"), Act("Shopping"), Act("Home", 1.1, 1.1))
    )

    val trips = HOVModeTransformer.splitToTrips(inputPlans)
    trips.size shouldBe 4
  }

  test("if plans do not start and end with home it should not be split to trips") {
    val inputPlans = newTrip(
      1,
      1,
      modes = Seq("HOV2", "HOV2", "HOV2", "WALK", "WALK", "CAR", "CAR"),
      activities = Seq(
        Act("Work", 1.1, 1.1),
        Act("Home"),
        Act("Work", 1.1, 1.2)
      )
    ) ++ newTrip(
      1,
      1,
      modes = Seq(WALK_TRANSIT.value, WALK.value, WALK.value),
      activities = Seq(Act("Home", 1.1, 1.1), Act("Work"), Act("Shopping"), Act("Home", 1.1, 1.1))
    )

    val trips = HOVModeTransformer.splitToTrips(inputPlans)
    trips.size shouldBe 1
  }

  test("trip without car and hov legs should not contain hov_car after transformation") {
    val fewPlans: Seq[PlanElement] = Seq(
      WALK,
      WALK_TRANSIT,
      BIKE,
      BIKE_TRANSIT,
      BUS,
      CABLE_CAR,
      FERRY,
      DRIVE_TRANSIT,
      RAIL,
      RIDE_HAIL,
      RIDE_HAIL_POOLED,
      RIDE_HAIL_TRANSIT,
      TRAM
    ).flatMap { mode =>
      newTrip(
        1,
        1,
        modes = Seq("hov2", "hov2", "hov3", mode.value, mode.value, "hov3", "hov3"),
        activities = Seq(
          Act("Home", 1.1, 1.1),
          Act("Shopping"),
          Act("Other"),
          Act("Work"),
          Act("Meal"),
          Act("Other"),
          Act("Shopping"),
          Act("Home", 1.1, 1.1)
        )
      )
    }

    val manyPlans = (1 to 50).flatMap(_ => fewPlans)

    val persons = Seq(newPerson(1, 1))
    val households = Seq(newHousehold(1, 1))
    val processedPlans = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(manyPlans, persons, households)

    val modes = processedPlans.flatMap(_.legMode).map(_.toLowerCase).toSet

    modes shouldNot contain("hov2")
    modes shouldNot contain("hov3")

    modes should contain(HOV2_TELEPORTATION.value.toLowerCase)
    modes should contain(HOV3_TELEPORTATION.value.toLowerCase)

    modes shouldNot contain(CAR_HOV2.value.toLowerCase)
    modes shouldNot contain(CAR_HOV3.value.toLowerCase)
  }

  test("trips with both hov and car must be forced to hov car") {
    val plans = newTrip(
      1,
      1,
      modes = Seq("HOV3", "HOV3", "HOV3", "CAR", "CAR"),
      activities =
        Seq(Act("Home", 1.1, 1.1), Act("Shopping"), Act("Other"), Act("Work"), Act("Shopping"), Act("Home", 1.1, 1.1))
    )

    val persons = Seq(newPerson(1, 1))
    val households = Seq(newHousehold(1, 1))
    val processedPlans = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plans, persons, households)
    val processedTrips = HOVModeTransformer.splitToTrips(processedPlans).toArray

    val tripsModes = processedTrips.map(_.flatMap(_.legMode))

    tripsModes.head shouldBe Seq("car_hov3", "car_hov3", "car_hov3", "CAR", "CAR")
  }

  test("trips with both hov and car must be forced to hov car, when car was left at some location and then picked up") {
    val plans = newTrip(
      1,
      1,
      modes = Seq("HOV2", "HOV2", "HOV2", "WALK", "WALK", "CAR", "CAR"),
      activities = Seq(
        Act("Home", 1.1, 1.1),
        Act("Shopping"),
        Act("Other"),
        Act("Work", 1.2, 1.2),
        Act("Meal"),
        Act("Work", 1.2, 1.2),
        Act("Shopping"),
        Act("Home", 1.1, 1.1)
      )
    )

    val persons = Seq(newPerson(1, 1))
    val households = Seq(newHousehold(1, 1))
    val processedPlans = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plans, persons, households)
    val processedTrips = HOVModeTransformer.splitToTrips(processedPlans).toArray

    val tripsModes = processedTrips.map(_.flatMap(_.legMode))

    tripsModes.head shouldBe Seq("car_hov2", "car_hov2", "car_hov2", "WALK", "WALK", "CAR", "CAR")
  }

  test("trip must not contain hov_car when car is lost on the go") {
    val plans = newTrip(
      1,
      1,
      modes = Seq("HOV2", "HOV2", "HOV3", "WALK", "WALK", "CAR", "CAR"),
      activities = Seq(
        Act("Home", 1.1, 1.1),
        Act("Shopping"),
        Act("Other"),
        Act("Work", 1.2, 1.2),
        Act("Meal"),
        Act("Other", 1.3, 1.3),
        Act("Shopping"),
        Act("Home", 1.1, 1.1)
      )
    )

    val persons = Seq(newPerson(1, 1))
    val households = Seq(newHousehold(1, 1))
    val processedPlans = HOVModeTransformer.transformHOVtoHOVCARorHOVTeleportation(plans, persons, households)
    val processedTrips = HOVModeTransformer.splitToTrips(processedPlans).toArray

    val tripsModes = processedTrips.map(_.flatMap(_.legMode))
    tripsModes.head shouldBe Seq(
      "hov2_teleportation",
      "hov2_teleportation",
      "hov3_teleportation",
      "WALK",
      "WALK",
      "CAR",
      "CAR"
    )
  }
}

object HOVModeTransformerTest {

  private val random = new Random()

  def newPerson(personId: Int, householdId: Int): PersonInfo =
    PersonInfo(PersonId(personId.toString), HouseholdId(householdId.toString), 0, 33, List(), isFemale = false, 42.0)

  def newHousehold(householdId: Int, numberOfCars: Int): HouseholdInfo =
    HouseholdInfo(HouseholdId(householdId.toString), numberOfCars, 90000.0, 10000.0, 20000.0)

  def newTrip(
    personId: Int,
    startIndex: Int,
    mode: String,
    activity1: Act = Act("Home"),
    activity2: Act = Act("Shopping")
  ): Iterable[PlanElement] = Seq(
    newActivity(personId, startIndex, activity1),
    newLeg(personId, startIndex + 1, mode),
    newActivity(personId, startIndex + 2, activity2),
    newLeg(personId, startIndex + 3, mode),
    newActivity(personId, startIndex + 4, activity1)
  )

  def newTrip(
    personId: Int,
    startIndex: Int,
    modes: Iterable[String],
    activities: Iterable[Act]
  ): Iterable[PlanElement] = {
    val firstActivity: PlanElement = newActivity(personId, startIndex, activities.head)
    val theRest = modes
      .zip(activities.tail)
      .flatMap { case (mode, act) =>
        Seq(newLeg(personId, startIndex + 1, mode), newActivity(personId, startIndex + 2, act))
      }
      .toSeq

    firstActivity +: theRest
  }

  def newLeg(personId: Int, planIndex: Int, mode: String): PlanElement = PlanElement(
    PersonId(personId.toString),
    0,
    0.0,
    planSelected = true,
    PlanElement.Leg,
    planIndex,
    None,
    None,
    None,
    None,
    Some(mode),
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    List(),
    None
  )

  def newActivity(personId: Int, planIndex: Int, act: Act): PlanElement =
    PlanElement(
      PersonId(personId.toString),
      0,
      0.0,
      planSelected = true,
      PlanElement.Activity,
      planIndex,
      Some(act.activity),
      Some(act.locationX),
      Some(act.locationY),
      Some(10.0),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      List(),
      None
    )

  case class Act(activity: String, locationX: Double = random.nextDouble(), locationY: Double = random.nextDouble())
}
