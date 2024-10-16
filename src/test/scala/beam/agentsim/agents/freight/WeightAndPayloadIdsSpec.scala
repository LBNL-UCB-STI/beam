package beam.agentsim.agents.freight

import beam.agentsim.events.PathTraversalEvent
import beam.sim.BeamHelper
import beam.utils.EventReader
import beam.utils.TestConfigUtils.testConfig
import beam.utils.matsim_conversion.MatsimPlanConversion.IdOps
import com.typesafe.config.ConfigFactory
import org.scalatest.AppendedClues.convertToClueful
import org.scalatest.Inspectors.forAll
import org.scalatest.matchers.must.Matchers
import org.scalatest.matchers.should.Matchers.convertToAnyShouldWrapper
import org.scalatest.wordspec.AnyWordSpecLike

class WeightAndPayloadIdsSpec extends AnyWordSpecLike with Matchers with BeamHelper {

  private val baseConf = ConfigFactory
    .parseString("""
        beam.agentsim.lastIteration = 0
        beam.agentsim.agents.freight.plansFilePath = "test/test-resources/beam/agentsim/freight/payload-plans.csv"
        beam.agentsim.agents.freight.toursFilePath = "test/test-resources/beam/agentsim/freight/freight-tours.csv"
        beam.agentsim.agents.freight.carriersFilePath = "test/test-resources/beam/agentsim/freight/freight-carriers.csv"
          """)
    .withFallback(testConfig("test/input/beamville/beam-freight.conf"))
    .resolve()

  val (matsimConfig, _, _) = runBeamWithConfig(baseConf)
  "Beam" when {
    "simulates freight with payload plans " must {
      "put payload ids and weights to PTE events" in {

        val filePath = EventReader.getEventsFilePath(matsimConfig, "events", "xml").getAbsolutePath
        val events = EventReader.fromXmlFile(filePath)
        val freight1: IndexedSeq[PathTraversalEvent] = events.collect {
          case pte: PathTraversalEvent if pte.driverId == "freightDriver-1" => pte
        }
        // freight 1 has 2 tours: 2 and 3
        // tour-2: 2 payloads (3, 4) of 1300 kg
        // tour-3: 3 payloads (5, 6, 7) of 1300, 1500 kg
        // the curb weight of the vehicle is 2200 kg

        // make sure that no walk pte carries a payload
        val walkPtes = freight1.filter(_.vehicleType == "BODY-TYPE-DEFAULT")
        walkPtes should have size 14
        forAll(walkPtes) { pte =>
          pte.weight shouldBe 70
        } withClue "weight of person body defined in vehicleTypes.csv is 70 kg"
        forAll(walkPtes) { pte => pte.payloadIds shouldBe empty } withClue "person should not carry any payloads"

        //make sure each freight leg has 2 pte with the same payloads
        val freightPtes = freight1.filter(_.vehicleType == "FREIGHT-1")
        freightPtes should have size 14
        forAll(freightPtes.grouped(2).toList) { case Seq(pte1, pte2) =>
          pte1.payloadIds shouldBe pte2.payloadIds
          pte1.weight shouldBe pte2.weight
        }
        freightPtes(0).payloadIds shouldBe empty
        freightPtes(0).weight shouldBe 2200
        freightPtes(2).payloadIds shouldBe IndexedSeq("payload-3").map(_.createId[PayloadPlan])
        freightPtes(2).weight shouldBe 2200 + 1300
        freightPtes(4).payloadIds shouldBe IndexedSeq("payload-3", "payload-4").map(_.createId[PayloadPlan])
        freightPtes(4).weight shouldBe 2200 + 2 * 1300
        freightPtes(6).payloadIds shouldBe empty
        freightPtes(6).weight shouldBe 2200
        freightPtes(8).payloadIds shouldBe IndexedSeq("payload-5").map(_.createId[PayloadPlan])
        freightPtes(8).weight shouldBe 2200 + 1300
        freightPtes(10).payloadIds shouldBe IndexedSeq("payload-5", "payload-6").map(_.createId[PayloadPlan])
        freightPtes(10).weight shouldBe 2200 + 1300 + 1500
        freightPtes(12).payloadIds shouldBe IndexedSeq("payload-5", "payload-6", "payload-7").map(
          _.createId[PayloadPlan]
        )
        freightPtes(12).weight shouldBe 2200 + 1300 + 2 * 1500
      }
    }
  }

}
