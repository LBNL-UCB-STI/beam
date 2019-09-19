package beam.utils.reflection

import java.lang.reflect.Modifier.{isAbstract, isInterface}
import java.lang.reflect.{Field, Modifier}

import akka.event.LoggingAdapter
import org.reflections.Reflections
import org.reflections.util.{ClasspathHelper, ConfigurationBuilder}

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
  * Created by dserdiuk on 5/19/17.
  */
trait ReflectionUtils {

  /**
    *
    * @return package name to scan in
    */
  def packageName: String

  lazy val reflections: Reflections = {
    val locationToLookIn = ClasspathHelper.forPackage(packageName)
    new Reflections(new ConfigurationBuilder().addUrls(locationToLookIn))
  }

  def classesOfType[T](implicit ct: ClassTag[T]): List[Class[T]] = {
    reflections
      .getSubTypesOf(ct.runtimeClass)
      .asScala
      .map(_.asInstanceOf[Class[T]])
      .toList
  }

  def concreteClassesOfType[T](implicit ct: ClassTag[T]): List[Class[T]] = {
    classesOfType[T](ct).filter(isConcrete)
  }

  def isConcrete[T](clazz: Class[T]): Boolean = {
    val modifiers = clazz.getModifiers
    !isAbstract(modifiers) && !isInterface(modifiers)
  }

  def isExtends[T](clazz: Class[_], subType: Class[T]): Boolean = {
    val allSuperTypes = org.reflections.ReflectionUtils.getAllSuperTypes(clazz)
    allSuperTypes.contains(subType)
  }
}

object ReflectionUtils {

  def setFinalField(clazz: Class[_], fieldName: String, value: Any): Unit = {
    val field: Field = clazz.getField(fieldName)
    field.setAccessible(true)
    val modifiersField: Field = classOf[Field].getDeclaredField("modifiers")
    modifiersField.setAccessible(true)
    modifiersField.setInt(field, field.getModifiers & ~Modifier.FINAL)
    field.set(null, value)
  }

  def logFields(
    log: LoggingAdapter,
    obj: Object,
    level: Int,
    ignoreFields: String = "",
    onlyPrintCollectionSize: Boolean = true,
    indent: String = ""
  ): Unit = {

    if (obj != null) {
      log.info(obj.getClass.getSimpleName + "->logFields")
      for (field <- (obj.getClass.getDeclaredFields ++ obj.getClass.getSuperclass.getDeclaredFields)) {
        field.setAccessible(true)
        val name = field.getName
        val value = field.get(obj)
        try {
          if ((!value.toString.contains("@") || value.isInstanceOf[String]) && !ignoreFields.contains(name)) {
            if (onlyPrintCollectionSize && field.getType.getName.equalsIgnoreCase("scala.collection.mutable.Map")) {
              log.info(indent + s"\t$name: ${value.asInstanceOf[scala.collection.mutable.Map[_, _]].size.toString}")
            } else {
              log.info(indent + s"\t$name: $value")
            }
          }

        } catch {
          case e: Exception =>
        }

        if (level > 0) {
          logFields(log, value, level - 1, ignoreFields, onlyPrintCollectionSize, indent + "\t")
        }
      }
    }
  }
}
