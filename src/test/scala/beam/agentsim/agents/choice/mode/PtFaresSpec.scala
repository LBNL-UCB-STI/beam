package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.mode.PtFares.loadPtFares
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

class PtFaresSpec extends FlatSpec with BeforeAndAfterAll {
  var ptf: PtFares = _

  override def beforeAll(): Unit = {
    ptf = PtFares(loadPtFares("test/input/beamville/test-data/ptFares-Expensive.csv"))
  }

  "PtFare of agency bus and route B2 for all ages" should " be $1" in {

    assert(ptf.getPtFare(Some("bus"), Some("B2"), None).getOrElse(0) == 1)

  }

  "PtFare of agency bus and route B2 for age 8 years" should " be $55" in {

    assert(ptf.getPtFare(Some("bus"), Some("B2"), Some(8)).getOrElse(0) == 55)

  }

  "PtFare of agency bus and route B3 for age 8 years" should " be $15" in {

    assert(ptf.getPtFare(Some("bus"), Some("B3"), Some(8)).getOrElse(0) == 15)

  }

  "PtFare of agency bus and route B2 for age 80 years" should " be $55" in {

    assert(ptf.getPtFare(Some("bus"), Some("B2"), Some(80)).getOrElse(0) == 55)

  }
}
