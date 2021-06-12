package beam.utils.shape

import java.lang.reflect.Method
import org.geotools.feature.simple.{SimpleFeatureBuilder, SimpleFeatureTypeBuilder}
import org.opengis.feature.simple.SimpleFeatureType
import org.opengis.referencing.crs.CoordinateReferenceSystem
import com.vividsolutions.jts.geom.{Geometry => JtsGeometry}

import java.util.Objects
import scala.reflect.ClassTag

class GenericFeatureBuilder[A <: Attributes](
  val attribToClass: Map[String, Class[_]],
  val attribToGetter: Map[String, Method],
  featureType: SimpleFeatureType
) extends SimpleFeatureBuilder(featureType) {

  def addAttributes(attributes: A): Unit = {
    attribToClass.keys.foreach { attrib =>
      val value = attribToGetter(attrib).invoke(attributes)
      add(GenericFeatureBuilder.fixIfPrimitive(value))
    }
  }
}

object GenericFeatureBuilder {
  private val scalaPrimitiveToJava: Map[Class[_], Class[_]] = Map(
    classOf[Byte]   -> classOf[java.lang.Byte],
    classOf[Short]  -> classOf[java.lang.Short],
    classOf[Int]    -> classOf[java.lang.Integer],
    classOf[Long]   -> classOf[java.lang.Long],
    classOf[Double] -> classOf[java.lang.Double]
  )

  private[shape] def fixIfPrimitive(v: Any): Any = {
    v match {
      case byte: Byte => java.lang.Byte.valueOf(byte)
      case s: Short   => java.lang.Short.valueOf(s)
      case i: Int     => java.lang.Integer.valueOf(i)
      case l: Long    => java.lang.Long.valueOf(l)
      case d: Double  => java.lang.Double.valueOf(d)
      case x          => x
    }
  }

  def create[T <: JtsGeometry, A <: Attributes](crs: CoordinateReferenceSystem, name: String)(
    implicit evG: ClassTag[T],
    evA: ClassTag[A],
  ): GenericFeatureBuilder[A] = {
    val isNoAttributes = Objects.equals(evA.runtimeClass, Attributes.EmptyAttributes.getClass)
    val attribToClazz: Map[String, Class[_]] =
      if (isNoAttributes) Map.empty
      else {
        evA.runtimeClass.getDeclaredFields.map { f =>
          val name = f.getName
          val `type`: Class[_] = f.getType
          val fixedType = scalaPrimitiveToJava.getOrElse(`type`, `type`)
          (name, fixedType)
        }.toMap
      }

    val attribToGetter: Map[String, Method] =
      if (isNoAttributes) Map.empty
      else {
        evA.runtimeClass.getMethods
          .filter(method => attribToClazz.contains(method.getName))
          .map { method =>
            (method.getName, method)
          }
          .toMap
      }
    create(crs, name, attribToClazz, attribToGetter)
  }

  def create[T <: JtsGeometry, A <: Attributes](
    crs: CoordinateReferenceSystem,
    name: String,
    attributes: Map[String, Class[_]],
    attribToGetter: Map[String, Method],
  )(implicit evG: ClassTag[T]): GenericFeatureBuilder[A] = {
    val b = new SimpleFeatureTypeBuilder
    b.setName(name)
    b.setCRS(crs)
    b.add("the_geom", evG.runtimeClass)
    attributes.foreach {
      case (name, clazz) =>
        b.add(name, clazz)
    }
    val featureType: SimpleFeatureType = b.buildFeatureType
    new GenericFeatureBuilder[A](attributes, attribToGetter, featureType)
  }
}
