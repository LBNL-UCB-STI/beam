package beam.utils

import org.scalatest.{Matchers, WordSpecLike}

class SummaryVehicleStatsParserTest extends WordSpecLike with Matchers {
  "stats parser " should {
    "split stats names " in {
      SummaryVehicleStatsParser.splitToStatVehicle("") shouldBe None
      SummaryVehicleStatsParser.splitToStatVehicle("some string") shouldBe None
      SummaryVehicleStatsParser.splitToStatVehicle("someString_") shouldBe None
      SummaryVehicleStatsParser.splitToStatVehicle("_someString") shouldBe None
      SummaryVehicleStatsParser.splitToStatVehicle("statName_vehicle_Typ_e") shouldBe Some("statName", "vehicle_Typ_e")
    }

    "parse full stats map " in {
      val stats = Map(
        "stat1_c_r"  -> 0.42,
        "stat1_bike" -> 0.42,
        "stat1_walk" -> 0.42,
        "stat2_bike" -> 0.42,
        "stat2_c_r"  -> 0.42,
        "stat2_walk" -> 0.42,
      )
      val parsedStats = Map(
        "c_r"  -> IndexedSeq(0.42, 0.42),
        "bike" -> IndexedSeq(0.42, 0.42),
        "walk" -> IndexedSeq(0.42, 0.42),
      )
      SummaryVehicleStatsParser.splitStatsMap(stats, Seq("stat1", "stat2"))._2 shouldBe parsedStats
    }

    "parse sparse stats map " in {
      val stats = Map(
        "stat1_c_r"  -> 0.42,
        "stat1_walk" -> 0.42,
        "stat2_bike" -> 0.42,
        "stat2_walk" -> 0.42,
        "stat3_TRAM" -> 0.42
      )
      val parsedStats = Map(
        "c_r"  -> IndexedSeq(0.42, 0.0, 0.0),
        "bike" -> IndexedSeq(0.0, 0.42, 0.0),
        "walk" -> IndexedSeq(0.42, 0.42, 0.0),
        "TRAM" -> IndexedSeq(0.0, 0.0, 0.42),
      )
      SummaryVehicleStatsParser.splitStatsMap(stats, Seq("stat1", "stat2", "stat3"))._2 shouldBe parsedStats
    }

    "ignore ignored stats " in {
      val stats = Map(
        "stat1_c_r"  -> 0.42,
        "stat1_walk" -> 0.42,
        "stat2_bike" -> 0.42,
        "stat2_walk" -> 0.42,
        "stat3_TRAM" -> 0.42
      )
      val (ignored, _) = SummaryVehicleStatsParser.splitStatsMap(stats, Seq("stat1", "stat3"))
      ignored.toSet shouldBe Set("stat2_bike", "stat2_walk")
    }

    "parse stats in correct order " in {
      val stats = Map(
        "stat1_c_r"  -> 1.0,
        "stat1_walk" -> 2.0,
        "stat2_bike" -> 3.0,
        "stat2_walk" -> 4.0,
        "stat3_TRAM" -> 5.0
      )
      val parsedStats = Map(
        "c_r"  -> IndexedSeq(0.0, 1.0, 0.0),
        "bike" -> IndexedSeq(0.0, 0.0, 3.0),
        "walk" -> IndexedSeq(0.0, 2.0, 4.0),
        "TRAM" -> IndexedSeq(5.0, 0.0, 0.0),
      )
      SummaryVehicleStatsParser.splitStatsMap(stats, Seq("stat3", "stat1", "stat2"))._2 shouldBe parsedStats
    }
  }
}
