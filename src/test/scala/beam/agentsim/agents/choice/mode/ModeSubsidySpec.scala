package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.mode.ModeSubsidy.loadSubsidies
import beam.router.Modes.BeamMode
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

class ModeSubsidySpec extends FlatSpec with BeforeAndAfterAll {
  var ms: ModeSubsidy = _

  override def beforeAll(): Unit = {
    ms = ModeSubsidy(loadSubsidies("test/input/beamville/subsidies.csv"))
  }

  "Subsidy for RIDE_HAIL under 10 years of age" should " be $7" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(5), None).getOrElse(0) == 7)

  }

  "Subsidy for RIDE_HAIL under 40k income" should " be $3" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(15), Some(30000)).getOrElse(0) == 3)

  }

  "Subsidy for RIDE_HAIL under 10 years of age and under 40k income" should " be $4" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(7), Some(30000)).getOrElse(0) == 4)

  }
}
