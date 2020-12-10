package beam.experiment

import java.io.{File, FileInputStream}
import java.nio.file.{Files, Path, Paths}

import beam.experiment.ExperimentApp.loadExperimentDefs
import org.yaml.snakeyaml.constructor.Constructor

/**
  * Prov
  */
class ExperimentApp extends App {
  val EXPERIMENTS_TAG = "experiments"

  def parseArgs(args: Array[String]) = {
    args
      .sliding(2, 1)
      .toList
      .collect {
        case Array("--experiments", filePath: String) if filePath.trim.nonEmpty =>
          (EXPERIMENTS_TAG, filePath)
        case arg @ _ =>
          throw new IllegalArgumentException(arg.mkString(" "))
      }
      .toMap
  }

  val argsMap = parseArgs(args)

  // Validate existence of experiments tag here:

  if (!argsMap.contains(EXPERIMENTS_TAG)) {
    throw new IllegalArgumentException(s"$EXPERIMENTS_TAG param is missing")
  }

  val experimentPath: Path = new File(argsMap(EXPERIMENTS_TAG)).toPath.toAbsolutePath

  if (!Files.exists(experimentPath)) {
    throw new IllegalArgumentException(s"$EXPERIMENTS_TAG file is missing: $experimentPath")
  }

  // Load experimentDef
  val experimentDef: ExperimentDef = loadExperimentDef(experimentPath.toFile)

  def validateExperimentConfig(experiment: ExperimentDef): Unit = {
    if (!Files.exists(Paths.get(experiment.header.beamTemplateConfPath))) {
      throw new IllegalArgumentException(
        s"Can't locate base beam config experimentFile at ${experiment.header.beamTemplateConfPath}"
      )
    }
  }

  def loadExperimentDef(experimentFileLoc: File): ExperimentDef = {
    val experimentDef = loadExperimentDefs(experimentFileLoc)
    validateExperimentConfig(experimentDef)
    experimentDef
  }

  def lastThingDoneInMain(): Unit = {}
  lastThingDoneInMain()
}

object ExperimentApp {

  def getExperimentPath(experimentLoc: String): Path = {

    val experimentPath: Path = new File(experimentLoc).toPath.toAbsolutePath

    if (!Files.exists(experimentPath)) {
      throw new IllegalArgumentException(s"Experiments file is missing: $experimentPath")
    }
    experimentPath
  }

  def loadExperimentDefs(file: File): ExperimentDef = {
    import org.yaml.snakeyaml.{TypeDescription, Yaml}
    val constructor = new Constructor(classOf[ExperimentDef])
    //Experiment.class is root
    val experimentDescription = new TypeDescription(classOf[ExperimentDef])
    experimentDescription.putListPropertyType("factors", classOf[Factor])
    constructor.addTypeDescription(experimentDescription)
    val factorsDescription = new TypeDescription(classOf[Factor])
    factorsDescription.putListPropertyType("levels", classOf[Level])
    constructor.addTypeDescription(factorsDescription)
    val levelDesc = new TypeDescription(classOf[Level])
    //levelDesc.putMapPropertyType("params", classOf[String], classOf[Any])
    constructor.addTypeDescription(levelDesc)
    val baseScenarioDesc = new TypeDescription(classOf[BaseScenario])
    factorsDescription.putListPropertyType("baseScenario", classOf[BaseScenario])
    constructor.addTypeDescription(baseScenarioDesc)
    val yaml = new Yaml(constructor)
    val experiment = yaml.loadAs(new FileInputStream(file), classOf[ExperimentDef])
    experiment
  }
}
