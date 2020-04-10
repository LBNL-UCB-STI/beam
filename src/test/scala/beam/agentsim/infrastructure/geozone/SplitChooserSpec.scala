package beam.agentsim.infrastructure.geozone

import org.scalatest.{Matchers, WordSpec}

class SplitChooserSpec extends WordSpec with Matchers {

  private val NotSplit: Int = 1

  "BreakdownPlan" should {

    "NOT SPLIT whenever bucketsGoal=2 and there are even buckets with same size" in {
//      val content = H3Content(
//        Map(
//          H3Index("81f2bffffffffff") -> Set(
//            H3Point(-87.91230637789121, 114.36846814258855),
//            H3Point(-89.24498931336583, 157.58548579731755)
//          ),
//          H3Index("81653ffffffffff") -> Set(
//            H3Point(4.642433992371294, 101.71178861137412),
//            H3Point(7.385246168451971, 100.82322080496418)
//          )
//        )
//      )
//      val plan: BreakdownPlan = BreakdownPlan(content, bucketsGoal = 2)

//      assertResult(true) {
//        plan.plans.forall { goal =>
//          goal.splitGoal == NotSplit
//        }
//      }
    }

    "NOT SPLIT whenever buckets has nothing to be split" in {
      val indexToStrings: Map[GeoIndex, Set[WgsCoordinate]] = Map(
        GeoIndex("81033ffffffffff") -> Set(WgsCoordinate(90.0, -99.61181940776801)),
        GeoIndex("81b57ffffffffff") -> Set(WgsCoordinate(-35.17155063934615, -163.2908517352389)),
        GeoIndex("81373ffffffffff") -> Set(WgsCoordinate(38.96802394893233, -148.88121342210718)),
      )

//      val contentWith10ElementsAtSameResolution = H3Content(indexToStrings)

//      val plan: BreakdownPlan = BreakdownPlan(contentWith10ElementsAtSameResolution, bucketsGoal = 8)
//
//      assertResult(true) {
//        plan.plans.forall { goal =>
//          goal.splitGoal == NotSplit
//        }
//      }
    }

    "SPLIT to 2 equally whenever there are 2 buckets with even elements and buckets goal equals to 4" in {
//      val content = H3Content(
//        Map(
//          H3Index("81f2bffffffffff") -> Set(
//            H3Point(-87.91230637789121, 114.36846814258855),
//            H3Point(-89.24498931336583, 157.58548579731755)
//          ),
//          H3Index("81653ffffffffff") -> Set(
//            H3Point(4.642433992371294, 101.71178861137412),
//            H3Point(7.385246168451971, 100.82322080496418)
//          )
//        )
//      )
//      val plan: BreakdownPlan = BreakdownPlan(content, bucketsGoal = 4)
//
//      assertResult(true) {
//        plan.plans.forall { goal =>
//          goal.splitGoal == 2
//        }
//      }
    }

    "SPLIT to 2 whenever there are 2 buckets with odd elements and buckets goal equals to 4" in {
//      val content = H3Content(
//        Map(
//          H3Index("81653ffffffffff") -> Set(
//            H3Point(4.642433992371294, 101.71178861137412),
//            H3Point(7.385246168451971, 100.82322080496418),
//            H3Point(2.9798395919834455, 98.02018336522767)
//          ),
//          H3Index("81033ffffffffff") -> Set(
//            H3Point(90.0, -99.61181940776801),
//            H3Point(86.7016228907434, -169.10341629270596),
//            H3Point(90.0, -15.161463921196875)
//          )
//        )
//      )
//      val plan: BreakdownPlan = BreakdownPlan(content, bucketsGoal = 4)
//
//      assertResult(true) {
//        plan.plans.forall { goal =>
//          goal.splitGoal == 2
//        }
//      }
    }

    "SPLIT considering distribution of points but also considering indexes cannot split are rounded up to 1" in {
      val indexA = GeoIndex("81033ffffffffff")
      val indexB = GeoIndex("81b57ffffffffff")
      val indexC = GeoIndex("81373ffffffffff")
      val indexAPoints = Set(
        WgsCoordinate(90.0, -99.61181940776801),
        WgsCoordinate(86.7016228907434, -169.10341629270596),
        WgsCoordinate(90.0, -15.161463921196875),
        WgsCoordinate(90.0, -40.57347268943054),
        WgsCoordinate(87.60904265202893, 153.57888248410174)
      )
//      val content = H3Content(
//        Map(
//          indexA -> indexAPoints,
//          indexB -> Set(H3Point(-35.17155063934615, -163.2908517352389)),
//          indexC -> Set(H3Point(38.96802394893233, -148.88121342210718)),
//        )
//      )
//      val indexesCannotSplit = content.buckets.count(_._2.size <= 1)
//      val bucketsGoal = 1 + Random.nextInt(10)
//      val plan: BreakdownPlan = BreakdownPlan(content, bucketsGoal = bucketsGoal)
//      val expectedPlanForIndexA = Math.max(Math.min(indexAPoints.size, bucketsGoal - indexesCannotSplit), 1)
//      assertResult(Some(BreakdownBucketGoal(indexA, expectedPlanForIndexA))) {
//        plan.plans.find(p => p.index == indexA)
//      }
//      assertResult(Some(BreakdownBucketGoal(indexB, NotSplit))) {
//        plan.plans.find(p => p.index == indexB)
//      }
//      assertResult(Some(BreakdownBucketGoal(indexC, NotSplit))) {
//        plan.plans.find(p => p.index == indexC)
//      }
    }
  }

}
