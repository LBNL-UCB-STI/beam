package beam.sim

object RunBeam extends BeamHelper {

  def main(args: Array[String]): Unit = {
    print("""
    |  ________
    |  ___  __ )__________ _______ ___
    |  __  __  |  _ \  __ `/_  __ `__ \
    |  _  /_/ //  __/ /_/ /_  / / / / /
    |  /_____/ \___/\__,_/ /_/ /_/ /_/
    |
    | _____________________________________
    |
     """.stripMargin)

    runBeamUsing(args)
    logger.info("Exiting BEAM")
    System.exit(0)
  }

}
