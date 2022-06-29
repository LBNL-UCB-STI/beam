package beam.router

import org.matsim.api.core.v01.Id
import org.matsim.api.core.v01.network.Link
import org.matsim.api.core.v01.population.Person
import org.matsim.vehicles.Vehicle
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import org.mockito.Mockito.{mock, when}

/**
  * @author Dmitry Openkov
  */
class LinkTravelTimeContainerSpec extends AnyWordSpecLike with Matchers {
  "LinkTravelTimeContainer" when {
    "provided with a valid linkstats file" should {
      "read travel time correctly" in {
        val container = new LinkTravelTimeContainer("test/test-resources/beam/router/0.linkstats.csv.gz", 3600, 30)
        val link = mock(classOf[Link])
        when(link.getId).thenReturn(Id.createLinkId(233))
        val person = mock(classOf[Person])
        val vehicle = mock(classOf[Vehicle])
        val travelTime = container.getLinkTravelTime(link, 4000, person, vehicle)
        travelTime shouldBe 0.355 +- 0.0001
      }
    }
  }
}
