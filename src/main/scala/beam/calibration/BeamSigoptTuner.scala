package beam.calibration

import java.io.File
import java.nio.file.{Files, Path, Paths}

import beam.experiment._
import com.google.common.collect.Lists
import com.sigopt.Sigopt
import com.sigopt.exception.SigoptException
import com.sigopt.model._
import com.typesafe.config.{ConfigFactory, ConfigValueFactory}

import scala.collection.JavaConverters



object BeamSigoptTuner extends App {

  import Bounded._

  // Fields //

  private val EXPERIMENTS_TAG = "experiments"
  private val CLIENT_ID_TAG = "client_token"

  val projectRoot: Path = {
    if (System.getenv("BEAM_ROOT") != null) {
      Paths.get(System.getenv("BEAM_ROOT"))
    } else {
      Paths.get("./").toAbsolutePath.getParent
    }
  }

  val argsMap = parseArgs(args)

  Sigopt.clientToken = argsMap(CLIENT_ID_TAG)

  val experimentPath: Path = new File(argsMap(EXPERIMENTS_TAG)).toPath.toAbsolutePath

  if (!Files.exists(experimentPath)) {
    throw new IllegalArgumentException(s"$EXPERIMENTS_TAG file is missing: $experimentPath")
  }

  val experimentDef = ExperimentGenerator.loadExperimentDefs(experimentPath.toFile)

  private val experiment: Experiment = createOrFetchExperiment(experimentPath)

  // METHODS //

  def parseArgs(args: Array[String]) = {
    args.sliding(2,1).toList.collect{
      case Array("--experiments", filePath:String) if filePath.trim.nonEmpty => (EXPERIMENTS_TAG, filePath)
      case Array("--client_token", clientId: String) if clientId.trim().nonEmpty =>
        (CLIENT_ID_TAG, clientId)
      case arg@_ =>
        throw new IllegalArgumentException(arg.mkString(" "))
    }.toMap
  }

  /**
    * Creates a new Sigopt [[Experiment]] based on the [[ExperimentDef]] model or else
    * fetches it from the online database.
    *
    * @return The fully instantiated [[Experiment]].
    * @throws SigoptException If the experiment cannot be created, this exception is thrown.
    */
  @throws[SigoptException]
  def createOrFetchExperiment(experimentPath: Path): Experiment = {

    val client = new Client(Sigopt.clientToken)
    val header = experimentDef.getHeader
    val experimentId = header.getTitle
    val optExperiment = client.experiments.list.call.getData.stream.filter((experiment: Experiment) => experiment.getId == experimentId).findFirst
    optExperiment.orElse(createExperiment(experimentDef))
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
  def createExperiment(beamExperiment: ExperimentDef): Experiment = {
    val header = beamExperiment.getHeader
    val experimentId = header.getTitle
    val factors = JavaConverters.asScalaIterator(beamExperiment.getFactors.iterator()).seq
    val parameters = Lists.newArrayList(JavaConverters.asJavaIterator(factors.map(factorToParameter)))

    Experiment.create.data(new Experiment.Builder().name(experimentId).parameters(parameters).build).call

  }


  def createConfigBasedOnAssignments(assignments: Assignments)= {

    // Build base config from file
    val baseConfig = ConfigFactory.parseFile(Paths.get(experimentDef.getHeader.getBeamTemplateConfPath).toFile)

    val runName = new StringBuilder

    val configParams = JavaConverters.iterableAsScalaIterable(assignments.entrySet()).seq.map{e=>e.getKey->e.getValue}.toMap

    val experimentBaseDir = experimentPath.getParent

    val runDirectory = projectRoot.relativize(Paths.get(experimentBaseDir.toFile.toString, "runs", runName.toString))

    val beamConfPath = projectRoot.relativize(Paths.get(runDirectory.toString, "beam.conf"))

    val beamOutputDir = projectRoot.relativize(Paths.get(runDirectory.toString, "output"))

    val runConfig = ( Map(
      "beam.agentsim.simulationName" -> "output",
      "beam.outputs.baseOutputDirectory" -> beamOutputDir.getParent.toString,
      "beam.outputs.addTimestampToOutputDirectory" -> "false",
      "beam.inputDirectory" -> experimentDef.getTemplateConfigParentDirAsString
    )++ configParams).foldLeft(baseConfig){
      case(prevConfig, (paramName, paramValue)) =>
        val configValue = ConfigValueFactory.fromAnyRef(paramValue)
        prevConfig.withValue(paramName, configValue)
    }
  }

  def getLevel(levelName: String, levels: java.util.List[Level]): Level = JavaConverters.collectionAsScalaIterable(levels).find(l =>{l.name.equals(levelName)}).orNull
}

