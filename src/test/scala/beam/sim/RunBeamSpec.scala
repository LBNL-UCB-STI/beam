package beam.sim

import org.scalatest.FlatSpec

/**
  * Created by Andrew A. Campbell on 11/3/17.
  */
class RunBeamSpec extends FlatSpec with RunBeam {

  "RunBeam" must "not crash running application/sf-light" in {
    val baseConfFile = Option("./application/sf-light/base-sf-light.conf")
    val rB = RunBeam
    System.out.println("CWD: " + System.getProperty("user.dir"))
    rB.rumBeamWithConfigFile(baseConfFile)
  }
}
