package beam.sim

object RunBeam extends BeamHelper {

  def matchConfigFile(args: Array[String]): String = args.toList match {
    case "--config" :: config :: _ => config
    case _ =>
      throw new IllegalArgumentException("Missing required argument: config")
  }

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

    val configFile: String = matchConfigFile(args)
    runBeamWithConfigFile(configFile)
    logger.info("Exiting BEAM")
  }

}
