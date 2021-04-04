package beam.experiment

import java.nio.file.{Files, Path, Paths}
import java.util.{Collections, List => JavaList, Map => JavaMap}
import java.util

import com.google.common.base.Charsets
import com.google.common.io.Resources
import scala.beans.BeanProperty
import scala.collection.JavaConverters._

case class ExperimentDef(
  @BeanProperty var runExperimentScript: String,
  @BeanProperty var batchRunScript: String,
  @BeanProperty var header: Header,
  @BeanProperty var defaultParams: java.util.Map[String, Object],
  @BeanProperty var factors: java.util.List[Factor]
) {

  lazy val projectRoot: Path = {
    if (System.getenv("BEAM_ROOT") != null) {
      Paths.get(System.getenv("BEAM_ROOT"))
    } else {
      Paths.get("./").toAbsolutePath.getParent
    }
  }

  def this() = this("", "", null, null, new java.util.LinkedList())

  def combinationsOfLevels(): List[ExperimentRun] = {

    val values = factors.asScala.map(factor => factor.levels.asScala.map(l => (l, factor))).toArray
    val runs = cartesian(values).toList
    runs.map { levels =>
      ExperimentRun(this, levels)
    }
  }

  private def cartesian[A](list: Seq[Seq[A]]): Iterator[Seq[A]] = {
    if (list.isEmpty) {
      Iterator(Seq())
    } else {
      list.head.iterator.flatMap { i =>
        cartesian(list.tail).map(i +: _)
      }
    }
  }

  /**
    *
    * @return list of distinct (factor_title, param_name)
    */
  def getDynamicParamNamesPerFactor: List[(String, String)] = {
    factors.asScala
      .flatMap(
        f => f.levels.asScala.flatMap(l => l.params.keySet().asScala.map(pname => (f.title, pname)))
      )
      .distinct
      .toList
  }

  def getRunScriptTemplate: String = {
    getTemplate(runExperimentScript, "runBeam.sh.tpl")
  }

  def getBatchRunScriptTemplate: String = {
    getTemplate(batchRunScript, "batchRunExperiment.sh.tpl")
  }

  private def getTemplate(script: String, resourceScript: String) = {
    if (script != null) {
      val scriptFile = Paths.get(script).toAbsolutePath
      if (!Files.exists(scriptFile)) {
        throw new IllegalArgumentException("No template script found " + scriptFile.toString)
      }
      scriptFile.toUri.toURL
    }

    Resources.toString(Resources.getResource(resourceScript), Charsets.UTF_8)
  }

  def getTemplateConfigParentDirAsString: String =
    Paths.get(header.beamTemplateConfPath).getParent.toAbsolutePath.toString
}

case class ExperimentRun(experiment: ExperimentDef, combinations: Seq[(Level, Factor)]) {

  lazy val params: Map[String, Any] = {
    val runParams = combinations.flatMap(_._1.params.asScala)

    val defaultParamsOpt = Option(experiment.defaultParams)

    val overrideParams = defaultParamsOpt match {
      case Some(defaultParams) => defaultParams.asScala.clone() ++ runParams
      case None                => runParams
    }

    overrideParams.toMap
  }

  lazy val levels: Map[String, String] = {
    combinations.map(tup => tup._2.title -> tup._1.name).toMap
  }

  lazy val name: String = {
    combinations.map(lf => s"${lf._2.title}_${lf._1.name}").mkString("__")
  }

  def getParam(name: String): Any = params(name)

  def getLevelTitle(name: String): String = levels(name)

  override def toString: String = {
    s"experiment-run: $name"
  }
}

case class Header(
  @BeanProperty var title: String,
  @BeanProperty var author: String,
  @BeanProperty var beamTemplateConfPath: String,
  @BeanProperty var beamScenarioDataInputRoot: String,
  @BeanProperty var experimentId: String,
  @BeanProperty var modeChoiceTemplate: String,
  @BeanProperty var numWorkers: String,
  @BeanProperty var deployParams: java.util.Map[String, Object],
  @BeanProperty var iterationForAnalysis: Int,
  @BeanProperty var processingConfPath: String,
  @BeanProperty var ownerEmail: String,
  @BeanProperty var customerFacingNotes: String,
  @BeanProperty var internalNotes: String,
) {
  def this() = this("", "", "", "", "", "", "", new java.util.HashMap(), 0, "", "", "", "")

  val experimentOutputRoot: String = s"data/interim/scenario_experiments"
}
case class BaseScenario(
  @BeanProperty var title: String,
  @BeanProperty var params: java.util.Map[String, Object]
) {
  def this() = this("", new java.util.HashMap())
}
