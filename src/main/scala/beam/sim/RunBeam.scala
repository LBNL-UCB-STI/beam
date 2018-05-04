package beam.sim

object RunBeam extends BeamHelper with App {
  print(
    """
  ________
  ___  __ )__________ _______ ___
  __  __  |  _ \  __ `/_  __ `__ \
  _  /_/ //  __/ /_/ /_  / / / / /
  /_____/ \___/\__,_/ /_/ /_/ /_/

 _____________________________________

 """)


  val argsMap = parseArgs(args)

  runBeamWithConfigFile(argsMap)
  logger.info("Exiting BEAM")
}
