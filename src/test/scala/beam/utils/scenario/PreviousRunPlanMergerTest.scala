package beam.utils.scenario

import org.mockito.Mockito.{mock, when}
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

import java.nio.file.Paths
import scala.util.Random

class PreviousRunPlanMergerTest extends AnyWordSpecLike with Matchers {

  private val mockedRandom = mock(classOf[Random])
  private val outputPath = Paths.get("test/test-resources/agentsim/plans/beamville")
  private val oldPlans = getOldPlans
  private val newPlans = getNewPlans

  "PreviousRunPlanMerger with fraction <= 0" should {

    "return same seq in case 1" in {
      val planMerger = new PreviousRunPlanMerger(-1, outputPath, "")

      val res = planMerger.merge(oldPlans)

      res should be(oldPlans)
    }

    "return same seq in case 2" in {
      val planMerger = new PreviousRunPlanMerger(0, outputPath, "")

      val res = planMerger.merge(oldPlans)

      res should be(oldPlans)
    }
  }

  "PreviousRunPlanMerger with fraction >= 0" should {

    "should return same seq case 1" in {
      val planMerger = new PreviousRunPlanMerger(0, outputPath, "")

      val res = planMerger.merge(oldPlans, newPlans, 0, new Random())

      res should be(oldPlans)
    }

    "should return same seq case 2" in {
      val planMerger = new PreviousRunPlanMerger(0.5, outputPath, "")

      val res = planMerger.merge(oldPlans, Seq(), 0.5, new Random())

      res should be(oldPlans)
    }

    "should return merged seq case 1" in {
      when(mockedRandom.nextInt).thenReturn(0)
      val planMerger = new PreviousRunPlanMerger(0.5, outputPath, "")

      val res = planMerger.merge(oldPlans, newPlans, 0.5, mockedRandom)

      res should be (Seq(
        createPlanElement("0", 0, 49508),
        createPlanElement("0", 1, 49509),
        createPlanElement("0", 2, 49511),
        createPlanElement("1", 0, 49501),
        createPlanElement("1", 1, 49502),
        createPlanElement("2", 0, 49512),
        createPlanElement("3", 0, 49505),
        createPlanElement("3", 1, 49506),
        createPlanElement("3", 2, 49507),
      ))
    }

    "should return merged seq case 2" in {
      val planMerger = new PreviousRunPlanMerger(1.1, outputPath, "")

      val res = planMerger.merge(oldPlans, newPlans, 1.1, new Random())

      res should be (Seq(
        createPlanElement("0", 0, 49508),
        createPlanElement("0", 1, 49509),
        createPlanElement("0", 2, 49511),
        createPlanElement("1", 0, 49501),
        createPlanElement("1", 1, 49502),
        createPlanElement("2", 0, 49512),
        createPlanElement("3", 0, 49513),
        createPlanElement("3", 1, 49514),
      ))
    }
  }

  def getOldPlans: Seq[PlanElement] = {
    Seq(
      createPlanElement("0", 0, 49499),
      createPlanElement("0", 1, 49500),
      createPlanElement("1", 0, 49501),
      createPlanElement("1", 1, 49502),
      createPlanElement("2", 0, 49503),
      createPlanElement("2", 1, 49504),
      createPlanElement("3", 0, 49505),
      createPlanElement("3", 1, 49506),
      createPlanElement("3", 2, 49507),
    )
  }

  def getNewPlans: Seq[PlanElement] = {
    Seq(
      createPlanElement("0", 0, 49508),
      createPlanElement("0", 1, 49509),
      createPlanElement("0", 2, 49511),
      createPlanElement("2", 0, 49512),
      createPlanElement("3", 0, 49513),
      createPlanElement("3", 1, 49514),
      createPlanElement("4", 0, 49515),
      createPlanElement("4", 1, 49516),
      createPlanElement("4", 2, 49517),
      createPlanElement("5", 0, 49518),
    )
  }

  def createPlanElement(personId: String, planIndex: Int, activityEndTime: Double): PlanElement =
    PlanElement(PersonId(personId), planIndex, 1, true, "activity", 0, Some("a"), Some(1), Some(1),
      Some(activityEndTime), None, None, None, None, None, None, None, None, Seq(), None)
}
