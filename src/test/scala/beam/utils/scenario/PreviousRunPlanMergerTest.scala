package beam.utils.scenario

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import scala.util.Random

class PreviousRunPlanMergerTest extends AnyWordSpecLike with Matchers {

  private val outputPath = Paths.get("test/test-resources/beam/agentsim/plans")
  private val oldPlans = getOldPlans
  private val newPlans = getNewPlans

  "PreviousRunPlanMerger with fraction <= 0" should {

    "should throw error when fraction is not within range [0, 1]" in {
      assertThrows[IllegalArgumentException] {
        new PreviousRunPlanMerger(-1, 1, outputPath, "", new Random(), identity)
      }
    }

    "should return same plans when fraction = 0" in {
      val planMerger = new PreviousRunPlanMerger(0, 1, outputPath, "", new Random(), identity)

      val (res, actuallyMerged) = planMerger.merge(newPlans)

      res should be(newPlans)
      actuallyMerged should be(false)
    }
  }

  "PreviousRunPlanMerger with fraction >= 0" should {

    "should return same plans when fraction = 0" in {
      val res = PreviousRunPlanMerger.merge(oldPlans, newPlans, 0, 0, new Random())

      res should be(oldPlans)
    }

    "should return same plans when activity sim plans empty" in {

      val res = PreviousRunPlanMerger.merge(oldPlans, Seq(), 0.5, 0, new Random())

      res should be(oldPlans)
    }

    "should return half of plans merged and all new plans when fraction = 0.5 and sample = 1" in {
      val res = PreviousRunPlanMerger.merge(oldPlans, newPlans, 0.5, 1.0, new Random(1))

      res.toSet.count(oldPlans.contains) should be(5)
      res.toSet.count(newPlans.contains) should be(12)

      res should be(
        Seq(
          createPlanElement("7", 0, 49501),
          createPlanElement("7", 1, 49502), //old as unique
          createPlanElement("2", 0, 49503),
          createPlanElement("2", 1, 49504), //old
          createPlanElement("6", 2, 49508), //old as unique
          createPlanElement("0", 0, 49499, planSelected = false),
          createPlanElement("0", 1, 49500, planSelected = false),
          createPlanElement("3", 0, 49505, planSelected = false),
          createPlanElement("3", 1, 49506, planSelected = false),
          createPlanElement("3", 2, 49507, planSelected = false),
          createPlanElement("4", 2, 49508, planSelected = false),
          createPlanElement("0", 0, 49509),
          createPlanElement("0", 1, 49510),
          createPlanElement("0", 2, 49511), //new merged
          createPlanElement("3", 0, 49513),
          createPlanElement("3", 1, 49514), //new merged
          createPlanElement("4", 0, 49515),
          createPlanElement("4", 1, 49516),
          createPlanElement("4", 2, 49517), //new merged
          createPlanElement("5", 0, 49518), //new added
          createPlanElement("8", 0, 49515), //new added
          createPlanElement("8", 1, 49516), //new added
          createPlanElement("8", 2, 49517) //new added
        )
      )
    }

    "should return half of plans merged and half of new plans when fraction = 0.5 and sample = 0.5" in {
      val res = PreviousRunPlanMerger.merge(oldPlans, newPlans, 0.5, 0.5, new Random(1))

      res.toSet.count(oldPlans.contains) should be(5)
      res.toSet.count(newPlans.contains) should be(9)

      res should be(
        Seq(
          createPlanElement("7", 0, 49501),
          createPlanElement("7", 1, 49502), //old as unique
          createPlanElement("2", 0, 49503),
          createPlanElement("2", 1, 49504), //old
          createPlanElement("6", 2, 49508), //old as unique
          createPlanElement("0", 0, 49499, planSelected = false),
          createPlanElement("0", 1, 49500, planSelected = false),
          createPlanElement("3", 0, 49505, planSelected = false),
          createPlanElement("3", 1, 49506, planSelected = false),
          createPlanElement("3", 2, 49507, planSelected = false),
          createPlanElement("4", 2, 49508, planSelected = false),
          createPlanElement("0", 0, 49509),
          createPlanElement("0", 1, 49510),
          createPlanElement("0", 2, 49511), //new merged
          createPlanElement("3", 0, 49513),
          createPlanElement("3", 1, 49514), //new merged
          createPlanElement("4", 0, 49515),
          createPlanElement("4", 1, 49516),
          createPlanElement("4", 2, 49517), //new merged
          createPlanElement("5", 0, 49518) //new added
        )
      )

    }

    "should return all plans merged and all new persons when fraction = 1.0" in {
      val res = PreviousRunPlanMerger.merge(oldPlans, newPlans, 1.0, 1.0, new Random(1))

      res.toSet.count(oldPlans.contains) should be(3)
      res.toSet.count(newPlans.contains) should be(13)

      res should be(
        Seq(
          createPlanElement("7", 0, 49501),
          createPlanElement("7", 1, 49502), //old as unique
          createPlanElement("6", 2, 49508), //old as unique
          createPlanElement("0", 0, 49499, planSelected = false),
          createPlanElement("0", 1, 49500, planSelected = false),
          createPlanElement("2", 0, 49503, planSelected = false),
          createPlanElement("2", 1, 49504, planSelected = false),
          createPlanElement("3", 0, 49505, planSelected = false),
          createPlanElement("3", 1, 49506, planSelected = false),
          createPlanElement("3", 2, 49507, planSelected = false),
          createPlanElement("4", 2, 49508, planSelected = false),
          createPlanElement("0", 0, 49509),
          createPlanElement("0", 1, 49510),
          createPlanElement("0", 2, 49511), //new merged
          createPlanElement("2", 0, 49512), //new merged
          createPlanElement("3", 0, 49513),
          createPlanElement("3", 1, 49514), //new merged
          createPlanElement("4", 0, 49515),
          createPlanElement("4", 1, 49516),
          createPlanElement("4", 2, 49517), //new merged
          createPlanElement("5", 0, 49518), //new added
          createPlanElement("8", 0, 49515), //new added
          createPlanElement("8", 1, 49516), //new added
          createPlanElement("8", 2, 49517) //new added
        )
      )
    }
  }

  "PreviousRunPlanMerger with valid inputs" should {
    "must read previous xml plans without error" in {
      val planMerger = new PreviousRunPlanMerger(1.0, 0.0, outputPath, "beamville", new Random(), identity)
      val activitySimPlans = getOldPlans //to avoid naming mess

      val (mergedPlans, merged) = planMerger.merge(activitySimPlans)

      merged should be(true)
      mergedPlans.filter { activitySimPlans.contains }.toList should be(
        Seq(
          createPlanElement("7", 0, 49501),
          createPlanElement("7", 1, 49502),
          createPlanElement("2", 0, 49503),
          createPlanElement("2", 1, 49504),
          createPlanElement("3", 0, 49505),
          createPlanElement("3", 1, 49506),
          createPlanElement("3", 2, 49507),
          createPlanElement("4", 2, 49508),
          createPlanElement("6", 2, 49508)
        )
      )

      mergedPlans.filter(_.personId.id == "1").toList should be(
        Seq(
          createActivityPlanElement("Home", 166321.9, 1568.87, 49500.0, "1", 0, -494.58068848294334),
          createLegPlanElement("walk", "1", 1, -494.58068848294334),
          createActivityPlanElement("Shopping", 167138.4, 1117.0, 56940.0, "1", 2, -494.58068848294334),
          createLegPlanElement("walk", "1", 3, -494.58068848294334),
          createActivityPlanElement("Home", 166321.9, 1568.87, 66621.0, "1", 4, -494.58068848294334),
          createLegPlanElement("bike", "1", 5, -494.58068848294334),
          createActivityPlanElement("Shopping", 166045.2, 2705.4, 71006.0, "1", 6, -494.58068848294334),
          createLegPlanElement("car", "1", 7, -494.58068848294334),
          createActivityPlanElement("Home", 166321.9, 1568.87, Double.NegativeInfinity, "1", 8, -494.58068848294334)
        )
      )
    }
  }

  def getOldPlans: Seq[PlanElement] = {
    Seq(
      createPlanElement("0", 0, 49499),
      createPlanElement("0", 1, 49500),
      createPlanElement("7", 0, 49501),
      createPlanElement("7", 1, 49502),
      createPlanElement("2", 0, 49503),
      createPlanElement("2", 1, 49504),
      createPlanElement("3", 0, 49505),
      createPlanElement("3", 1, 49506),
      createPlanElement("3", 2, 49507),
      createPlanElement("4", 2, 49508),
      createPlanElement("6", 2, 49508)
    )
  }

  def getNewPlans: Seq[PlanElement] = {
    Seq(
      createPlanElement("0", 0, 49509),
      createPlanElement("0", 1, 49510),
      createPlanElement("0", 2, 49511),
      createPlanElement("2", 0, 49512),
      createPlanElement("3", 0, 49513),
      createPlanElement("3", 1, 49514),
      createPlanElement("4", 0, 49515),
      createPlanElement("4", 1, 49516),
      createPlanElement("4", 2, 49517),
      createPlanElement("5", 0, 49518),
      createPlanElement("8", 0, 49515),
      createPlanElement("8", 1, 49516),
      createPlanElement("8", 2, 49517)
    )
  }

  def createPlanElement(
    personId: String,
    planIndex: Int,
    activityEndTime: Double,
    planSelected: Boolean = true
  ): PlanElement = {
    PlanElement(
      "",
      PersonId(personId),
      planIndex,
      1,
      planSelected = planSelected,
      "activity",
      0,
      Some("a"),
      Some(1),
      Some(1),
      Option(activityEndTime),
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      None,
      Seq(),
      None
    )
  }

  def createActivityPlanElement(
    activityType: String,
    x: Double,
    y: Double,
    endTime: Double,
    personId: String,
    planElementIdx: Int,
    planScore: Double
  ): PlanElement = PlanElement(
    "",
    PersonId(personId),
    0,
    planScore,
    planSelected = true,
    "activity",
    planElementIdx,
    Some(activityType),
    Some(x),
    Some(y),
    Option(endTime),
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    None,
    Seq.empty,
    None
  )

  def createLegPlanElement(mode: String, personId: String, planElementIdx: Int, planScore: Double): PlanElement =
    PlanElement(
      "",
      PersonId(personId),
      0,
      planScore,
      planSelected = true,
      "leg",
      planElementIdx,
      None,
      None,
      None,
      None,
      Some(mode),
      Some("-Infinity"),
      Some("-Infinity"),
      None,
      None,
      None,
      None,
      None,
      Seq.empty,
      None
    )
}
