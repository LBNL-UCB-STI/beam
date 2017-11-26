package beam.experiment

import scala.beans.BeanProperty
import scala.collection.JavaConverters._


case class ExperimentDef(@BeanProperty var title: String,
                         @BeanProperty var author: String,
                         @BeanProperty var beamTemplateConfPath: String,
                         @BeanProperty var runExperimentScript: String,
                         @BeanProperty var modeChoiceTemplate: String,

                         @BeanProperty var baseScenario: BaseScenario,
                         @BeanProperty var factors: java.util.List[Factor]) {
  def this() = this("", "", "", "", "", null, new java.util.LinkedList())

  def combinationsOfLevels() = {

    val values = factors.asScala.map(factor => factor.levels.asScala.map(l => (l, factor))).toArray
    val runs = cartesian(values).toList
    runs.map { levels =>
      ExperimentRun(baseScenario, levels)
    }
  }

  private def cartesian[A](list: Seq[Seq[A]]): Iterator[Seq[A]] = {
    if (list.isEmpty) {
      Iterator(Seq())
    } else {
      list.head.iterator.flatMap { i => cartesian(list.tail).map(i +: _) }
    }
  }

  /**
    *
    * @return list of distinct (factor_title, param_name)
    */
  def getDynamicParamNamesPerFactor() = {
    factors.asScala.flatMap(f => f.levels.asScala.flatMap(l => l.params.keySet().asScala.map(pname => (f.title, pname)))).distinct.toList
  }
}

case class ExperimentRun(baseScenario: BaseScenario, combinations: Seq[(Level, Factor)]) {

  lazy val params: Map[String, Any] = {
    val runParams = combinations.flatMap(_._1.params.asScala)
    val overrideParams = baseScenario.params.asScala.clone() ++ runParams
    overrideParams.toMap
  }
  lazy val name: String = {
    if (combinations.isEmpty) {
      "baseScenario__" + baseScenario.title
    } else {
      combinations.map(lf => s"${lf._2.title}__${lf._1.name}").mkString("___")
    }
  }

  def getParam(name: String) = params(name)

  override def toString: String = {
    s"experiment-run: $name"
  }
}

case class BaseScenario(@BeanProperty var title: String, @BeanProperty var params: java.util.Map[String, Object]) {
  def this() = this("", new java.util.HashMap())
}