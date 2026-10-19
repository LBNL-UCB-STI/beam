package beam.utils

import com.typesafe.config.ConfigFactory
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike

class ConfigConsistencyComparatorSpec extends AnyWordSpecLike with Matchers {

  "ConfigConsistencyComparator.findNotFoundFiles" should {
    "skip beam.agentsim.agents.freight.* filepath checks when freight is disabled" in {
      val conf = ConfigFactory.parseString(
        """
          |beam.agentsim.agents.freight.enabled = false
          |beam.agentsim.agents.freight.plansFilePath = "/non/existent/freight/plans.csv"
          |beam.agentsim.agents.freight.toursFilePath = "/non/existent/freight/tours.csv"
          |beam.agentsim.agents.freight.carriersFilePath = "/non/existent/freight/carriers.csv"
          |beam.agentsim.agents.vehicles.vehicleTypesFilePath = "/non/existent/vehicleTypes.csv"
          |""".stripMargin
      )

      val missing = ConfigConsistencyComparator.findNotFoundFiles(conf)
      missing.map(_._1) should contain only "beam.agentsim.agents.vehicles.vehicleTypesFilePath"
    }

    "check beam.agentsim.agents.freight.* filepaths when freight is enabled" in {
      val conf = ConfigFactory.parseString(
        """
          |beam.agentsim.agents.freight.enabled = true
          |beam.agentsim.agents.freight.plansFilePath = "/non/existent/freight/plans.csv"
          |beam.agentsim.agents.freight.toursFilePath = "/non/existent/freight/tours.csv"
          |""".stripMargin
      )

      val missing = ConfigConsistencyComparator.findNotFoundFiles(conf)
      missing.map(_._1) should contain allOf (
        "beam.agentsim.agents.freight.plansFilePath",
        "beam.agentsim.agents.freight.toursFilePath"
      )
    }
  }
}
