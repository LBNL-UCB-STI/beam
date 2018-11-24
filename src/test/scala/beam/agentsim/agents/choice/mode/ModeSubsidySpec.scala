package beam.agentsim.agents.choice.mode

import beam.agentsim.agents.choice.mode.ModeSubsidy.loadSubsidies
import beam.router.Modes.BeamMode
import org.scalatest.{BeforeAndAfterAll, FlatSpec}

class ModeSubsidySpec extends FlatSpec with BeforeAndAfterAll {
  var ms: ModeSubsidy = _

  override def beforeAll(): Unit = {
    ms = ModeSubsidy(loadSubsidies("test/input/beamville/subsidies.csv"), null)
  }

  "Subsidy for RIDE_HAIL under 10 years of age" should " be $7" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(5), None, None, None).getOrElse(0) == 7)

  }

  "Subsidy for RIDE_HAIL under 40k income" should " be $3" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(15), Some(30000), None, None).getOrElse(0) == 3)

  }

  "Subsidy for RIDE_HAIL under 10 years of age and under 40k income" should " be $4" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(7), Some(30000), None, None).getOrElse(0) == 4)

  }

  "Subsidy for RIDE_HAIL with agency id 1" should " be $3" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(25), Some(30000), Some("1"), None).getOrElse(0) == 3)

  }

  "Subsidy for RIDE_HAIL with agency id 1 and route id 2" should " be $7" in {

    assert(ms.getSubsidy(BeamMode.RIDE_HAIL, Some(25), Some(30000), Some("1"), Some("2")).getOrElse(0) == 7)

  }
}
