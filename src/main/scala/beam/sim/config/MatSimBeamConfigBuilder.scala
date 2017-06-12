package beam.sim.config

import java.nio.file.Paths

import com.typesafe.config.{Config, ConfigList, ConfigUtil}
import org.matsim.core.api.internal.MatsimParameters
import org.matsim.core.config.{ConfigGroup, ConfigUtils}
import org.slf4j.{Logger, LoggerFactory}

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Util builder to create MATSim config from TypeSafe config.
  * It uses reflect to find available modules/parameter sets in classpath.
  * The builder follows MATSim naming convention. Thus it expects typesafe conf like this:
  *
  *  matsim.modules {
  *      global {
  *        randomSeed = 4711
  *        coordinateSystem = "Atlantis"
  *      }
  *      network {
  *        inputNetworkFile = "multimodalnetwork.xml"
  *      }
  * }
  * As it's described names of submodules properties need to be the same as MATSim module names
  *
  * @author dserdiuk
  */
class MatSimBeamConfigBuilder(beamConf: Config) {
  private val logger: Logger = LoggerFactory.getLogger(classOf[MatSimBeamConfigBuilder])

  def buildMatSamConf() = {
    val matSimConfig = ConfigUtils.createConfig(Paths.get(beamConf.origin().url().toURI).getParent.toUri.toURL)
    val paramSetClassCache = RefectionUtils.concreteClassesOfType[MatsimParameters].
      collect { case clazz if RefectionUtils.isExtends(clazz, classOf[ConfigGroup]) =>
        Try(clazz.newInstance()).toOption
      }.flatten.map(paramSet => {
           val group = paramSet.asInstanceOf[ConfigGroup]
          (group.getName, group.getClass.asInstanceOf[Class[ConfigGroup]])
      }).filterNot(_._2.getName.contains("Old")) // there 2 version of ActivityParams class in different packages, remove old one
      .toMap

    beamConf.getConfig("matsim.modules").entrySet().asScala.map(entry => {
      val moduleAndProp = ConfigUtil.splitPath(entry.getKey).asScala.toList
      (moduleAndProp, entry.getValue)
    }).filter(i => i._1.length > 1).foreach{ case (moduleName :: List(prop), value) =>
      Option(matSimConfig.getModules.get(moduleName)) match {
        case Some(configGroup) if prop.equalsIgnoreCase("parameterset")  =>
          value match {
            case list: ConfigList =>
              list.unwrapped()
              val unwrappedParamSets  = list.asScala.map(paramSet => paramSet.unwrapped().asInstanceOf[java.util.Map[String, _]].asScala).toList
              unwrappedParamSets.foreach( parameterSet => {
                parameterSet.get("type") match {
                  case Some(paramSetType) =>
                    paramSetClassCache.get(paramSetType.toString).foreach( paramSetClazz => {
                      val paramSetInstance = paramSetClazz.newInstance()
                      val paramSetProperties = parameterSet.filterNot(_._1 == "type")
                      if (paramSetProperties.nonEmpty) {
                        paramSetProperties.foreach{ case (paramName, paramSetValue) =>
                          paramSetInstance.addParam(paramName, paramSetValue.toString)
                        }
                        configGroup.addParameterSet(paramSetInstance)
                      } else {
                        logger.warn(s"Configuration is malformed. Empty parameterset in ${unwrappedParamSets.mkString(",")}")
                      }
                    })
                  case None =>
                    logger.warn(s"Configuration is malformed. Failed to find type of parameterset in ${unwrappedParamSets.mkString(",")}")
                }
              })
          }
        case Some(configGroup) =>
          configGroup.addParam(prop, value.unwrapped().toString)
        case None =>
          logger.warn(s"MATSim module '$moduleName' not found")
      }
    }
    matSimConfig
  }
}
