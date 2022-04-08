package scripts

import org.scalatest.funsuite.AnyFunSuite
import org.scalatest.matchers.should.Matchers

class AddTAZIdToGeneratedPlansTest extends AnyFunSuite with Matchers {

  test("testAddTAZIdToActivitiesLocations") {
    val pathToGeneratedPlans = "test/test-resources/scripts/generatedPlans_sflight1k.csv.gz"
    val pathToTAZCenters = "test/input/sf-light/taz-centers.csv.gz"
    val maybeCRS = Some("epsg:26910")

    val expectedFirst21Row = Seq(
      "tripId,personId,planIndex,planScore,planSelected,planElementType,planElementIndex,activityType,activityLocationX,activityLocationY,activityStartTime,activityEndTime,legMode,legDepartureTime,legTravelTime,legRouteType,legRouteStartLink,legRouteEndLink,legRouteTravelTime,legRouteDistance,legRouteLinks,activityLocationTAZ",
      ",010100-2012000596480-0-179581,0,0.0,true,activity,0,Home,551130.2960340485,4184682.7295167553,-Infinity,34440.0,,,,,,,,,\"\",100281",
      ",010100-2012000596480-0-179581,0,0.0,true,leg,1,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179581,0,0.0,true,activity,2,Shopping,552251.7401971443,4182200.3515556245,-Infinity,44520.0,,,,,,,,,\"\",10002R",
      ",010100-2012000596480-0-179581,0,0.0,true,leg,3,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179581,0,0.0,true,activity,4,Home,551130.2960340485,4184682.7295167553,-Infinity,-Infinity,,,,,,,,,\"\",100281",
      ",010100-2012000596480-0-179582,0,0.0,true,activity,0,Home,551130.2960340485,4184682.7295167553,-Infinity,27720.0,,,,,,,,,\"\",100281",
      ",010100-2012000596480-0-179582,0,0.0,true,leg,1,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179582,0,0.0,true,activity,2,Other,552582.6495843935,4182680.1544600977,-Infinity,55860.0,,,,,,,,,\"\",10002B",
      ",010100-2012000596480-0-179582,0,0.0,true,leg,3,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179582,0,0.0,true,activity,4,Home,551130.2960340485,4184682.7295167553,-Infinity,61860.0,,,,,,,,,\"\",100281",
      ",010100-2012000596480-0-179582,0,0.0,true,leg,5,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179582,0,0.0,true,activity,6,Social,553223.956307973,4182342.7639438845,-Infinity,64140.0,,,,,,,,,\"\",100102",
      ",010100-2012000596480-0-179582,0,0.0,true,leg,7,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179582,0,0.0,true,activity,8,Home,551130.2960340485,4184682.7295167553,-Infinity,-Infinity,,,,,,,,,\"\",100281",
      ",010100-2012000596480-0-179583,0,0.0,true,activity,0,Home,551130.2960340485,4184682.7295167553,-Infinity,19500.0,,,,,,,,,\"\",100281",
      ",010100-2012000596480-0-179583,0,0.0,true,leg,1,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179583,0,0.0,true,activity,2,Work,547817.7165682092,4175651.870513485,-Infinity,50760.0,,,,,,,,,\"\",101065",
      ",010100-2012000596480-0-179583,0,0.0,true,leg,3,,,,,,,-Infinity,-Infinity,,,,,,\"\",",
      ",010100-2012000596480-0-179583,0,0.0,true,activity,4,Home,551130.2960340485,4184682.7295167553,-Infinity,-Infinity,,,,,,,,,\"\",100281",
      ",010100-2012000596480-0-179584,0,0.0,true,activity,0,Home,551130.2960340485,4184682.7295167553,-Infinity,28980.0,,,,,,,,,\"\",100281"
    )

    val (header, rowsIterator) =
      AddTAZIdToGeneratedPlans.addTAZIdToActivitiesLocations(pathToGeneratedPlans, pathToTAZCenters, maybeCRS)
    val rows = Seq(header) ++ rowsIterator.toSeq
    rows.size shouldBe 11939
    rows.take(21) shouldBe expectedFirst21Row
  }

}
