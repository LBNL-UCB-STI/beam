package beam.utils

import java.lang.reflect.Modifier.{isAbstract, isInterface}
import java.lang.reflect.{Field, Modifier}

import com.google.common.collect.Lists
import org.reflections.util.{ClasspathHelper, ConfigurationBuilder}
import org.reflections.{ReflectionUtils, Reflections}
import org.reflections.util.ClasspathHelper
import org.reflections.util.ConfigurationBuilder

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

/**
  * Created by dserdiuk on 5/19/17.
  */
object RefectionUtils {

  val reflections = {
    val classLoader = RefectionUtils.getClass.getClassLoader
    val builder = new ConfigurationBuilder
    builder.addUrls(ClasspathHelper.forPackage(".jnilib"))
    new Reflections(builder.addUrls(ClasspathHelper.forClassLoader(classLoader)).addClassLoader(classLoader))
  }

  def classesOfType[T](implicit ct: ClassTag[T]): List[Class[T]] = {
    reflections.getSubTypesOf(ct.runtimeClass).asScala.map(_.asInstanceOf[Class[T]]).toList
  }

  def concreteClassesOfType[T](implicit ct: ClassTag[T]): List[Class[T]] = {
    classesOfType[T](ct).filter(isConcrete)
  }

  def isConcrete[T](clazz: Class[T]) = {
    val modifiers = clazz.getModifiers
    !isAbstract(modifiers) && !isInterface(modifiers)
  }

  def isExtends[T](clazz: Class[_], subType: Class[T]): Boolean = {
    val allSuperTypes = ReflectionUtils.getAllSuperTypes(clazz)
    allSuperTypes.contains(subType)
  }

  def setFinalField(clazz: Class[_], fieldName: String, value: Any) = {
    val field: Field = clazz.getField(fieldName)
    field.setAccessible(true)
    val modifiersField: Field = classOf[Field].getDeclaredField("modifiers")
    modifiersField.setAccessible(true)
    modifiersField.setInt(field, field.getModifiers & ~Modifier.FINAL)
    field.set(null, value)
  }


}
