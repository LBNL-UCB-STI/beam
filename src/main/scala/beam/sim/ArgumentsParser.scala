package beam.sim

import beam.utils.BeamConfigUtils
import com.typesafe.config.{Config => TypesafeConfig}
import scopt.OptionParser

object ArgumentsParser {

  private def buildParser: OptionParser[Arguments] = {
    new scopt.OptionParser[Arguments]("beam") {
      opt[String]("config")
        .action(
          (value, args) => {
            args.copy(
              config = Some(BeamConfigUtils.parseFileSubstitutingInputDirectory(value)),
              configLocation = Option(value)
            )
          }
        )
        .validate(
          value =>
            if (value.trim.isEmpty) failure("config location cannot be empty")
            else success
        )
        .text("Location of the beam config file")
      opt[String]("cluster-type")
        .action(
          (value, args) =>
            args.copy(
              clusterType = value.trim.toLowerCase match {
                case "master" => Some(Master)
                case "worker" => Some(Worker)
                case _        => None
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
      opt[String]("seed-address")
        .action((value, args) => args.copy(seedAddress = Option(value)))
        .validate(
          value =>
            if (value.trim.isEmpty) failure("seed-address cannot be empty")
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

      checkConfig(
        args =>
          if (args.useCluster && (args.nodeHost.isEmpty || args.nodePort.isEmpty || args.seedAddress.isEmpty))
            failure("If using the cluster then node-host, node-port, and seed-address are required")
          else if (args.useCluster && !args.useLocalWorker.getOrElse(true))
            failure("If using the cluster then use-local-worker MUST be true (or unprovided)")
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
    clusterType: Option[ClusterType] = None,
    nodeHost: Option[String] = None,
    nodePort: Option[String] = None,
    seedAddress: Option[String] = None,
    useLocalWorker: Option[Boolean] = None,
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

}
