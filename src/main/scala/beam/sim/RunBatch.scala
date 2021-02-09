package beam.sim

import java.io.File
import java.nio.file.{Files, Path}

import beam.utils.BeamConfigUtils

import scala.collection.JavaConverters._

object RunBatch extends App with BeamHelper {
  val BATCH_OPT = "batch"

  val argsMap = parseArgs(args)

  if (!argsMap.contains(BATCH_OPT)) {
    throw new IllegalArgumentException(s"$BATCH_OPT param is missing")
  }

  val batchPath: Path = new File(argsMap(BATCH_OPT)).toPath.toAbsolutePath

  if (!Files.exists(batchPath)) {
    throw new IllegalArgumentException(s"$BATCH_OPT file is missing: $batchPath")
  }

  // Run batch
  runBatch(batchPath)

  System.exit(0)

  def parseArgs(args: Array[String]) = {
    args
      .sliding(2, 1)
      .toList
      .collect {
        case Array("--batch", filePath: String) if filePath.trim.nonEmpty =>
          (BATCH_OPT, filePath)
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  def runBatch(batchPath: Path): Unit = {
    val batchConfig = BeamConfigUtils.parseFileSubstitutingInputDirectory(batchPath.toFile).resolve()

    val baseConfPath = batchConfig.getString("batch.baseConfig")
    val baseConf = BeamConfigUtils.parseFileSubstitutingInputDirectory(baseConfPath)

    val plans = batchConfig.getConfigList("batch.plans")

    for (plan <- plans.asScala) {
      val conf = plan.withFallback(baseConf).resolve()

      runBeamWithConfig(conf, None)
    }
  }
}
