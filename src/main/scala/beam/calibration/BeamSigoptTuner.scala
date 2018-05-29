package beam.calibration

import java.io.File
import java.nio.file.{Path, Paths}

import beam.experiment._
import com.google.common.collect.Lists
import com.sigopt.Sigopt
import com.sigopt.exception.SigoptException
import com.sigopt.model._
import com.typesafe.config.{Config, ConfigFactory, ConfigValueFactory}

import scala.collection.JavaConverters

case class SigoptExperimentData(experimentDef: ExperimentDef, experimentPath: File){

  lazy val projectRoot: Path = {
    if (System.getenv("BEAM_ROOT") != null) {
      Paths.get(System.getenv("BEAM_ROOT"))
    } else {
      Paths.get("./").toAbsolutePath.getParent
    }
  }


}

object BeamSigoptTuner {

  import Bounded._

  // Fields //

  /**
    * Creates a new Sigopt [[Experiment]] based on the [[ExperimentDef]] model or else
    * fetches it from the online database.
    *
    * @return The fully instantiated [[Experiment]].
    * @throws SigoptException If the experiment cannot be created, this exception is thrown.
    */
  @throws[SigoptException]
  def createOrFetchExperiment(experimentData: SigoptExperimentData): Experiment = {
    val client = new Client(Sigopt.clientToken)
    val header = experimentData.experimentDef.getHeader
    val experimentId = header.getTitle
    val optExperiment = client.experiments.list.call.getData.stream.filter((experiment: Experiment) => experiment.getId == experimentId).findFirst
    optExperiment.orElse(createExperiment(experimentData))
  }


  /**
    * Converts a [[Factor factor]] to a SigOpt [[Parameter parameters]]
    * assuming that there are [[Level levels]] with High and Low names.
    *
    * The type of the parameter values is equivalent to the first key of the `High`
    * [[Level level]] `param`.
    *
    * @param factor [[Factor]] to convert
    * @return The factor as a SigOpt [[Parameter parameter]]
    */
  def factorToParameter(factor: Factor): Parameter = {

    val levels = factor.levels

    val highLevel = getLevel("High", levels)

    val paramName: String = highLevel.params.keySet().iterator().next()

    val maxValue = highLevel.params.get(paramName)
    val lowLevel = getLevel("Low", levels)
    val minValue = lowLevel.params.get(paramName)

    val parameter = new Parameter.Builder().name(paramName)

    // Build bounds
    maxValue match {
      case _: Double =>
        parameter.bounds(getBounds(minValue.asInstanceOf[Double], maxValue.asInstanceOf[Double])).`type`("double")
      case _: Int =>
        parameter.`type`("int").bounds(getBounds(minValue.asInstanceOf[Int], maxValue.asInstanceOf[Int]))
      case _ =>
        throw new RuntimeException("Type error!")
    }

    parameter.build()

  }

  @throws[SigoptException]
  def createExperiment(experimentData: SigoptExperimentData): Experiment = {
    val header = experimentData.experimentDef.getHeader
    val experimentId = header.getTitle
    val factors = JavaConverters.asScalaIterator(experimentData.experimentDef.getFactors.iterator()).seq
    val parameters = Lists.newArrayList(JavaConverters.asJavaIterator(factors.map(factorToParameter)))
    Experiment.create.data(new Experiment.Builder().name(experimentId).parameters(parameters).build).call

  }


  def createConfigBasedOnAssignments(assignments: Assignments, experimentData: SigoptExperimentData, runName: String): Config = {
    // Build base config from file
    val baseConfig = ConfigFactory.parseFile(Paths.get(experimentData.experimentDef.getHeader.getBeamTemplateConfPath).toFile)

    val configParams = JavaConverters.iterableAsScalaIterable(assignments.entrySet()).seq.map { e => e.getKey -> e.getValue }.toMap

    val experimentBaseDir = experimentData.experimentPath.getParent

    val runDirectory = experimentData.projectRoot.relativize(Paths.get(experimentBaseDir.toString, "runs", runName))

    val beamConfPath = experimentData.projectRoot.relativize(Paths.get(runDirectory.toString, "beam.conf"))

    val beamOutputDir: Path = experimentData.projectRoot.relativize(Paths.get(runDirectory.toString, "output"))

    (Map(
      "beam.agentsim.simulationName" -> "output",
      "beam.outputs.baseOutputDirectory" -> beamOutputDir.getParent.toString,
      "beam.outputs.addTimestampToOutputDirectory" -> "false",
      "beam.inputDirectory" -> experimentData.experimentDef.getTemplateConfigParentDirAsString
    ) ++ configParams).foldLeft(baseConfig) {
      case (prevConfig, (paramName, paramValue)) =>
        val configValue = ConfigValueFactory.fromAnyRef(paramValue)
        prevConfig.withValue(paramName, configValue)
    }
  }

  private def getLevel(levelName: String, levels: java.util.List[Level]): Level = JavaConverters.collectionAsScalaIterable(levels).find(l => {
    l.name.equals(levelName)
  }).orNull


}

