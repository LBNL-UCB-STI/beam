package beam.utils.scenario

import org.matsim.core.utils.misc.OptionalTime
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import scala.util.Random
import beam.utils.OptionalUtils.OptionalTimeExtension

class PreviousRunPlanMergerTest extends AnyWordSpecLike with Matchers {

  private val outputPath = Paths.get("test/test-resources/beam/agentsim/plans")
  private val oldPlans = getOldPlans
  private val newPlans = getNewPlans

  "PreviousRunPlanMerger with fraction <= 0" should {

    "should throw error when fraction is not within range [0, 1]" in {
      assertThrows[IllegalArgumentException] {
        new PreviousRunPlanMerger(-1, 1, Some(5), outputPath, "", new Random(), identity)
      }
    }

    "should return same plans when fraction = 0" in {
      val planMerger = new PreviousRunPlanMerger(0, 1, Some(5), outputPath, "", new Random(), identity)

      val (res, actuallyMerged) = planMerger.merge(newPlans)

      res should be(newPlans)
      actuallyMerged should be(false)
    }
  }

  "PreviousRunPlanMerger with fraction >= 0" should {

    "should return same plans when fraction = 0" in {
      val res = PreviousRunPlanMerger
        .merge(oldPlans, newPlans, 0, 0, Some(5), new Random())
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      val oldPlansSorted = oldPlans.toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      res should be(oldPlansSorted)
    }

    "should return same plans when activity sim plans empty" in {

      val res = PreviousRunPlanMerger
        .merge(oldPlans, Seq(), 0.5, 0, Some(5), new Random())
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      val oldPlansSorted = oldPlans.toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      res should be(oldPlansSorted)
    }

    "should return half of plans merged and all new plans when fraction = 0.5 and sample = 1" in {
      val res = PreviousRunPlanMerger
        .merge(oldPlans, newPlans, 0.5, 1.0, Some(5), new Random(1))
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))

      res.toSet.count(oldPlans.contains) should be(5)
      res.toSet.count(newPlans.contains) should be(12)

      res should be(
        Seq(
          createPlanElement("7", 0, 0, 49501),
          createPlanElement("7", 0, 1, 49502), //old as unique
          createPlanElement("6", 0, 0, 49508), //old as unique
          createPlanElement("0", 1, 0, 49499, planSelected = false),
          createPlanElement("0", 1, 1, 49500, planSelected = false),
          createPlanElement("2", 0, 0, 49503), // kept because not selected
          createPlanElement("2", 0, 1, 49504),
          createPlanElement("3", 1, 0, 49509, score = 50, planSelected = false),
          createPlanElement("3", 1, 1, 49510, score = 50, planSelected = false),
          createPlanElement("3", 1, 2, 49511, score = 50, planSelected = false),
          createPlanElement("3", 2, 0, 49505, planSelected = false),
          createPlanElement("3", 2, 1, 49506, planSelected = false),
          createPlanElement("3", 2, 2, 49507, planSelected = false),
          createPlanElement("4", 1, 0, 49508, planSelected = false),
          createPlanElement("0", 0, 0, 49509),
          createPlanElement("0", 0, 1, 49510),
          createPlanElement("0", 0, 2, 49511), //new merged
          //          createPlanElement("2", 0, 0, 49512), //new merged but not selected
          createPlanElement("3", 0, 0, 49513),
          createPlanElement("3", 0, 1, 49514), //new merged
          createPlanElement("4", 0, 0, 49515),
          createPlanElement("4", 0, 1, 49516),
          createPlanElement("4", 0, 2, 49517), //new merged
          createPlanElement("5", 0, 0, 49518), //new added
          createPlanElement("8", 0, 0, 49515), //new added
          createPlanElement("8", 0, 1, 49516), //new added
          createPlanElement("8", 0, 2, 49517) //new added
        ).toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      )
    }

    "should return half of plans merged and half of new plans when fraction = 0.5 and sample = 0.5" in {
      val res = PreviousRunPlanMerger
        .merge(oldPlans, newPlans, 0.5, 0.5, Some(5), new Random(1))
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))

      res.toSet.count(oldPlans.contains) should be(5)
      res.toSet.count(newPlans.contains) should be(9)

      res should be(
        Seq(
          createPlanElement("7", 0, 0, 49501),
          createPlanElement("7", 0, 1, 49502), //old as unique
          createPlanElement("6", 0, 0, 49508), //old as unique
          createPlanElement("0", 1, 0, 49499, planSelected = false),
          createPlanElement("0", 1, 1, 49500, planSelected = false),
          createPlanElement("2", 0, 0, 49503), // kept because not selected
          createPlanElement("2", 0, 1, 49504),
          createPlanElement("3", 1, 0, 49509, score = 50, planSelected = false),
          createPlanElement("3", 1, 1, 49510, score = 50, planSelected = false),
          createPlanElement("3", 1, 2, 49511, score = 50, planSelected = false),
          createPlanElement("3", 2, 0, 49505, planSelected = false),
          createPlanElement("3", 2, 1, 49506, planSelected = false),
          createPlanElement("3", 2, 2, 49507, planSelected = false),
          createPlanElement("4", 1, 0, 49508, planSelected = false),
          createPlanElement("0", 0, 0, 49509),
          createPlanElement("0", 0, 1, 49510),
          createPlanElement("0", 0, 2, 49511), //new merged
//          createPlanElement("2", 0, 0, 49512), //new merged but not selected
          createPlanElement("3", 0, 0, 49513),
          createPlanElement("3", 0, 1, 49514), //new merged
          createPlanElement("4", 0, 0, 49515),
          createPlanElement("4", 0, 1, 49516),
          createPlanElement("4", 0, 2, 49517), //new merged
          createPlanElement("5", 0, 0, 49518) //new added
//          createPlanElement("8", 0, 0, 49515), //new added
//          createPlanElement("8", 0, 1, 49516), //new added
//          createPlanElement("8", 0, 2, 49517) //new added
        ).toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      )

    }

    "should return all plans merged and all new persons when fraction = 1.0" in {
      val res = PreviousRunPlanMerger
        .merge(oldPlans, newPlans, 1.0, 1.0, Some(5), new Random(1))
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))

      res.toSet.count(oldPlans.contains) should be(3)
      res.toSet.count(newPlans.contains) should be(13)

      res should be(
        Seq(
          createPlanElement("7", 0, 0, 49501),
          createPlanElement("7", 0, 1, 49502), //old as unique
          createPlanElement("6", 0, 0, 49508), //old as unique
          createPlanElement("0", 1, 0, 49499, planSelected = false),
          createPlanElement("0", 1, 1, 49500, planSelected = false),
          createPlanElement("2", 1, 0, 49503, planSelected = false),
          createPlanElement("2", 1, 1, 49504, planSelected = false),
          createPlanElement("3", 1, 0, 49509, score = 50, planSelected = false),
          createPlanElement("3", 1, 1, 49510, score = 50, planSelected = false),
          createPlanElement("3", 1, 2, 49511, score = 50, planSelected = false),
          createPlanElement("3", 2, 0, 49505, planSelected = false),
          createPlanElement("3", 2, 1, 49506, planSelected = false),
          createPlanElement("3", 2, 2, 49507, planSelected = false),
          createPlanElement("4", 1, 0, 49508, planSelected = false),
          createPlanElement("0", 0, 0, 49509),
          createPlanElement("0", 0, 1, 49510),
          createPlanElement("0", 0, 2, 49511), //new merged
          createPlanElement("2", 0, 0, 49512), //new merged
          createPlanElement("3", 0, 0, 49513),
          createPlanElement("3", 0, 1, 49514), //new merged
          createPlanElement("4", 0, 0, 49515),
          createPlanElement("4", 0, 1, 49516),
          createPlanElement("4", 0, 2, 49517), //new merged
          createPlanElement("5", 0, 0, 49518), //new added
          createPlanElement("8", 0, 0, 49515), //new added
          createPlanElement("8", 0, 1, 49516), //new added
          createPlanElement("8", 0, 2, 49517) //new added
        ).toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      )
    }

    "should trim low score plans when fraction = 1.0 and maximum plan memory size is 1" in {
      val res = PreviousRunPlanMerger
        .merge(oldPlans, newPlans, 1.0, 1.0, Some(1), new Random(1))
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))

      res.toSet.count(oldPlans.contains) should be(3)
      res.toSet.count(newPlans.contains) should be(13)

      res should be(
        Seq(
          createPlanElement("7", 0, 0, 49501),
          createPlanElement("7", 0, 1, 49502), //old as unique
          createPlanElement("6", 0, 0, 49508), //old as unique
          createPlanElement("0", 1, 0, 49499, planSelected = false),
          createPlanElement("0", 1, 1, 49500, planSelected = false),
          createPlanElement("2", 1, 0, 49503, planSelected = false),
          createPlanElement("2", 1, 1, 49504, planSelected = false),
//          createPlanElement("3", 1, 0, 49505, planSelected = false), // Trimmed
//          createPlanElement("3", 1, 1, 49506, planSelected = false),
//          createPlanElement("3", 1, 2, 49507, planSelected = false),
          createPlanElement("3", 1, 0, 49509, score = 50, planSelected = false),
          createPlanElement("3", 1, 1, 49510, score = 50, planSelected = false),
          createPlanElement("3", 1, 2, 49511, score = 50, planSelected = false),
          createPlanElement("4", 1, 0, 49508, planSelected = false),
          createPlanElement("0", 0, 0, 49509),
          createPlanElement("0", 0, 1, 49510),
          createPlanElement("0", 0, 2, 49511), //new merged
          createPlanElement("2", 0, 0, 49512), //new merged
          createPlanElement("3", 0, 0, 49513),
          createPlanElement("3", 0, 1, 49514), //new merged
          createPlanElement("4", 0, 0, 49515),
          createPlanElement("4", 0, 1, 49516),
          createPlanElement("4", 0, 2, 49517), //new merged
          createPlanElement("5", 0, 0, 49518), //new added
          createPlanElement("8", 0, 0, 49515), //new added
          createPlanElement("8", 0, 1, 49516), //new added
          createPlanElement("8", 0, 2, 49517) //new added
        ).toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      )
    }

  }

  "PreviousRunPlanMerger with valid inputs" should {
    "must read previous xml plans without error" in {
      val planMerger = new PreviousRunPlanMerger(1.0, 0.0, Some(5), outputPath, "beamville", new Random(), identity)
      val activitySimPlans = {
        getOldPlans.filter(_.planSelected).map { case plan => plan.copy(planIndex = 0) } //to avoid naming mess
        // Assumption here is that activitysim plans always are selected and have planIndex = 0
      }

      val (mergedPlans, merged) = planMerger.merge(activitySimPlans)

      merged should be(true)
      mergedPlans
        .filter { activitySimPlans.contains }
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex)) should be(
        Seq(
//          createPlanElement("0", 0, 0, 49499), // This agent doesn't exist in the merged plans and sampleFraction = 0 so we don't take him
//          createPlanElement("0", 0, 1, 49500),
          createPlanElement("7", 0, 0, 49501),
          createPlanElement("7", 0, 1, 49502),
          createPlanElement("2", 0, 0, 49503),
          createPlanElement("2", 0, 1, 49504),
          createPlanElement("3", 0, 0, 49509, score = 50),
          createPlanElement("3", 0, 1, 49510, score = 50),
          createPlanElement("3", 0, 2, 49511, score = 50),
          createPlanElement("4", 0, 0, 49508),
          createPlanElement("6", 0, 0, 49508)
        ).toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      )

      mergedPlans
        .filter(_.personId.id == "1")
        .toList
        .sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex)) should be(
        Seq(
          createActivityPlanElement("Home", 166321.9, 1568.87, OptionalTime.defined(49500.0), "1", 0, -494.58068848294334),
          createLegPlanElement("walk", "1", 1, -494.58068848294334),
          createActivityPlanElement("Shopping", 167138.4, 1117.0, OptionalTime.defined(56940.0), "1", 2, -494.58068848294334),
          createLegPlanElement("walk", "1", 3, -494.58068848294334),
          createActivityPlanElement("Home", 166321.9, 1568.87, OptionalTime.defined(66621.0), "1", 4, -494.58068848294334),
          createLegPlanElement("bike", "1", 5, -494.58068848294334),
          createActivityPlanElement("Shopping", 166045.2, 2705.4, OptionalTime.defined(71006.0), "1", 6, -494.58068848294334),
          createLegPlanElement("car", "1", 7, -494.58068848294334),
          createActivityPlanElement("Home", 166321.9, 1568.87, OptionalTime.undefined(), "1", 8, -494.58068848294334)
        ).toList.sortBy(p => (p.personId.id.toInt, p.planIndex, p.planElementIndex))
      )
    }
  }

  def getOldPlans: Seq[PlanElement] = {
    Seq(
      createPlanElement("0", 0, 0, 49499),
      createPlanElement("0", 0, 1, 49500),
      createPlanElement("7", 0, 0, 49501),
      createPlanElement("7", 0, 1, 49502),
      createPlanElement("2", 0, 0, 49503),
      createPlanElement("2", 0, 1, 49504),
      createPlanElement("3", 0, 0, 49505, score = 0, planSelected = false),
      createPlanElement("3", 0, 1, 49506, score = 0, planSelected = false),
      createPlanElement("3", 0, 2, 49507, score = 0, planSelected = false),
      createPlanElement("3", 1, 0, 49509, score = 50, planSelected = true),
      createPlanElement("3", 1, 1, 49510, score = 50, planSelected = true),
      createPlanElement("3", 1, 2, 49511, score = 50, planSelected = true),
      createPlanElement("4", 0, 0, 49508),
      createPlanElement("6", 0, 0, 49508)
    )
  }

  def getNewPlans: Seq[PlanElement] = {
    Seq(
      createPlanElement("0", 0, 0, 49509),
      createPlanElement("0", 0, 1, 49510),
      createPlanElement("0", 0, 2, 49511),
      createPlanElement("2", 0, 0, 49512),
      createPlanElement("3", 0, 0, 49513),
      createPlanElement("3", 0, 1, 49514),
      createPlanElement("4", 0, 0, 49515),
      createPlanElement("4", 0, 1, 49516),
      createPlanElement("4", 0, 2, 49517),
      createPlanElement("5", 0, 0, 49518),
      createPlanElement("8", 0, 0, 49515),
      createPlanElement("8", 0, 1, 49516),
      createPlanElement("8", 0, 2, 49517)
    )
  }

  def createPlanElement(
    personId: String,
    planIndex: Int,
    planElementIndex: Int,
    activityEndTime: Double,
    planSelected: Boolean = true,
    score: Double = 0.0
  ): PlanElement = {
    PlanElement(
      "",
      PersonId(personId),
      planIndex,
      score,
      planSelected = planSelected,
      PlanElement.Activity,
      planElementIndex,
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
    endTime: OptionalTime,
    personId: String,
    planElementIdx: Int,
    planScore: Double
  ): PlanElement = PlanElement(
    "",
    PersonId(personId),
    0,
    planScore,
    true,
    PlanElement.Activity,
    planElementIdx,
    Some(activityType),
    Some(x),
    Some(y),
    endTime.toOption,
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
      true,
      PlanElement.Leg,
      planElementIdx,
      None,
      None,
      None,
      None,
      Some(mode),
      Some(OptionalTime.undefined().toString),
      Some(OptionalTime.undefined().toString),
      None,
      None,
      None,
      None,
      None,
      Seq.empty,
      None
    )
}
