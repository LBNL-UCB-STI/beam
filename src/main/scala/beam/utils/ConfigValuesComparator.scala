package beam.utils

import com.typesafe.config.ConfigRenderOptions
import com.typesafe.scalalogging.LazyLogging

import scala.collection.JavaConverters._

/*
Application expects to get two config files as input
args(0) - config file #1
args(1) - config file #2

The output of application will contains the difference between values of two provided config files.
Configs will be resolved, all included configs will be parsed too and all nested config values will be used in comparison.
The order of config keys in the config files does not matter, but, if config entry contains list of values
and the order in the list is different, then it will be considered as difference.

./gradlew :execute -PmainClass=beam.utils.ConfigValuesComparator -PappArgs="['test/input/sf-light/sf-light-1k.conf','test/input/sf-light/sf-light-5k.conf']"
 */
object ConfigValuesComparator extends LazyLogging {

  def getFullConfigMap(configFileLocation: String): Predef.Map[String, String] = {
    val renderOptions = ConfigRenderOptions.concise()
    val config = BeamConfigUtils.parseFileSubstitutingInputDirectory(configFileLocation)
    config
      .resolve()
      .entrySet()
      .asScala
      .map(x => x.getKey -> x.getValue.render(renderOptions))
      .toMap
  }

  def compareValues(pathToConfig1: String, pathToConfig2: String): Unit = {
    val configMap1 = getFullConfigMap(pathToConfig1)
    val configMap2 = getFullConfigMap(pathToConfig2)

    val allKeys = (configMap1.keys ++ configMap2.keys).toSet.toSeq.sorted
    allKeys.foreach { key =>
      val value1 = configMap1.getOrElse(key, "")
      val value2 = configMap2.getOrElse(key, "")
      if (value1 != value2) {
        println(key)
        println(s"\t\t'$value1'")
        println(s"\t\t'$value2'")
      }
    }

    println(s"Compared ${allKeys.size} config keys")
  }

  def main(args: Array[String]): Unit = {
    if (args.length != 2) {
      throw new RuntimeException("Run params should contain paths to two config files.")
    }

    val configFile1 = args(0)
    val configFile2 = args(1)

    println(s"Comparison of values of two config files.")
    println(s"Format of the output is the following:")
    println(s"<Config key>")
    println(s"\t\t<Config value from first config file> [$configFile1]")
    println(s"\t\t<Config value from second config file> [$configFile2]")

    compareValues(configFile1, configFile2)
  }
}
