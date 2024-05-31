package beam.sim

import beam.utils.BeamConfigUtils
import com.typesafe.config.{Config => TypesafeConfig}
import scopt.OptionParser

object ArgumentsParser {

  private def buildParser: OptionParser[Arguments] = {
    new scopt.OptionParser[Arguments]("beam") {
      opt[String]('c', "config")
        .action((value, args) => {
          args.copy(
            config = Some(BeamConfigUtils.parseFileSubstitutingInputDirectory(value)),
            configLocation = Option(value)
          )
        })
        .validate(value =>
          if (value.trim.isEmpty) failure("config location cannot be empty")
          else success
        )
        .text("Location of the beam config file")
      opt[String]('p', "python-executable")
        .action((value, args) => args.copy(pythonExecutable = Option(value).map(_.trim)))
        .text("A python executable to be used with analytic scripts")
      opt[String]("cluster-type")
        .action((value, args) =>
          args.copy(
            clusterType = value.trim.toLowerCase match {
              case "master"     => Some(Master)
              case "worker"     => Some(Worker)
              case "sim-worker" => Some(SimWorker)
              case _            => None
            }
          )
        )
        .text("If running as a cluster, specify master or worker")
      opt[String]("node-host")
        .action((value, args) => args.copy(nodeHost = Option(value)))
        .validate(value => if (value.trim.isEmpty) failure("node-host cannot be empty") else success)
        .text("Host used to run the remote actor system")
      opt[String]("node-port")
        .action((value, args) => args.copy(nodePort = Option(value)))
        .validate(value => if (value.trim.isEmpty) failure("node-port cannot be empty") else success)
        .text("Port used to run the remote actor system")
      opt[Int]("part-number")
        .action((value, args) => args.copy(partNumber = Option(value)))
        .validate(value => if (value < 0) failure("part number cannot be below zero") else success)
        .text("Simulation part number. sim-worker simulate only a single part of the all the agents.")
      opt[Int]("total-parts")
        .action((value, args) => args.copy(totalParts = Option(value)))
        .validate(value => if (value < 1) failure("total parts must be greater then zero") else success)
        .text("Number of total parts of simulation.")
      opt[Seq[String]]("seed-addresses")
        .valueName("<seed1>,<seed2>...")
        .action((value, args) => args.copy(seedAddresses = value))
        .validate(value =>
          if (value.isEmpty) failure("seed-addresses cannot be empty")
          else success
        )
        .text(
          "Comma separated list of initial addresses used for the rest of the cluster to bootstrap"
        )
      opt[Boolean]("use-local-worker")
        .action((value, args) => args.copy(useLocalWorker = Some(value)))
        .text(
          "Boolean determining whether to use a local worker. " +
          "If cluster is NOT enabled this defaults to true and cannot be false. " +
          "If cluster is specified then this defaults to false and must be explicitly set to true. " +
          "NOTE: For cluster, this will ONLY be checked if cluster-type=master"
        )

      checkConfig(args =>
        if (args.useCluster && (args.nodeHost.isEmpty || args.nodePort.isEmpty || args.seedAddresses.isEmpty))
          failure("If using the cluster then node-host, node-port, and seed-address are required")
        else if (args.useCluster && !args.useLocalWorker.getOrElse(true))
          failure("If using the cluster then use-local-worker MUST be true (or unprovided)")
        else if (args.useCluster && args.clusterType.isEmpty)
          failure("If using the cluster then cluster-type should be defined")
        else if (
          args.useCluster && args.clusterType
            .contains(SimWorker) && (args.partNumber.isEmpty || args.totalParts.isEmpty)
        )
          failure("If it's a sim-worker then part-number and total-parts must be defined")
        else success
      )
    }
  }

  private def parseArguments(parser: OptionParser[Arguments], args: Array[String]): Option[Arguments] = {
    parser.parse(args, init = Arguments())
  }

  def parseArguments(args: Array[String]): Option[Arguments] = parseArguments(buildParser, args)

  case class Arguments(
    configLocation: Option[String] = None,
    config: Option[TypesafeConfig] = None,
    pythonExecutable: Option[String] = None,
    clusterType: Option[ClusterType] = None,
    nodeHost: Option[String] = None,
    nodePort: Option[String] = None,
    seedAddresses: Seq[String] = Seq.empty,
    useLocalWorker: Option[Boolean] = None,
    partNumber: Option[Int] = None,
    totalParts: Option[Int] = None,
    customStatsProcessorClass: Option[String] = None
  ) {
    val useCluster: Boolean = clusterType.isDefined
  }

  sealed trait ClusterType

  case object Master extends ClusterType {
    override def toString = "master"
  }

  case object Worker extends ClusterType {
    override def toString = "worker"
  }

  case object SimWorker extends ClusterType {
    override def toString = "sim-worker"
  }

}
