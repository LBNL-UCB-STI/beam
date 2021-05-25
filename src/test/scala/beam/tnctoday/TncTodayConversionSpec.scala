package beam.tnctoday

import com.typesafe.scalalogging.StrictLogging
import json.converter.{TazOutput, TncToday}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper

class TncTodayConversionSpec extends AnyWordSpecLike with Matchers with StrictLogging {

  private lazy val completedStats = TncToday.completeStats(inputData)
  private lazy val statsTotals = TncToday.generateTotals(completedStats)

  private lazy val inputData = Seq(
    TazOutput.TazStats(1l, 0, "00:00:00", 0.5, 0.9),
    TazOutput.TazStats(1l, 0, "01:00:00", 0.2, 0.3),
    TazOutput.TazStats(1l, 0, "02:00:00", 0.3, 0.7),
    TazOutput.TazStats(1l, 0, "03:00:00", 0.4, 0.6),
    TazOutput.TazStats(1l, 0, "04:00:00", 0.2, 0.2),
    // dropOffs = 1.6 - pickups = 2.7
    TazOutput.TazStats(1l, 1, "01:00:00", 0.2, 0.3),
    TazOutput.TazStats(1l, 1, "02:00:00", 0.6, 0.6),
    TazOutput.TazStats(1l, 1, "04:00:00", 0.1, 0.9),
    TazOutput.TazStats(1l, 1, "07:00:00", 0.9, 0.0)
    // dropOffs = 1.8 - pickups = 1.8
  )

  "TncToday class " must {
    "Complete stats for all hours in a day and for all days in the week in completeStats method" in {
      completedStats.size shouldBe 168

      inputData.forall(completedStats.contains) shouldBe true
      completedStats should contain allElementsOf inputData

      val groupedByDay = completedStats.groupBy(_.day_of_week)
      groupedByDay.size shouldBe 7

      val allDays: Seq[String] = (0 to 23).map(i => "%02d:00:00".format(i))

      groupedByDay.foreach {
        case (_, statsByWeek) =>
          statsByWeek.size shouldBe 24
          statsByWeek.map(_.time) should contain theSameElementsAs allDays
      }
    }
    "Generate totals in generateTotals method" in {
      statsTotals.size shouldBe 7
      val byDay = statsTotals.map(_.day_of_week)
      byDay should contain theSameElementsAs (0 to 6)
      val totals = Map(
        0 -> (1.6d, 2.7d),
        1 -> (1.8d, 1.8d),
        2 -> (0d, 0d),
        3 -> (0d, 0d),
        4 -> (0d, 0d),
        5 -> (0d, 0d),
        6 -> (0d, 0d)
      )
      val totalsMap =
        statsTotals.map(e => (e.day_of_week, (e.dropoffs, e.pickups)))
      totalsMap should contain theSameElementsAs totals

    }
  }

}
