package beam.utils.json

import io.circe.Decoder.Result
import io.circe.{Decoder, Encoder, HCursor, Json}
import org.matsim.api.core.v01.Id

import scala.reflect.ClassTag

class IdFormat[T](implicit val ct: ClassTag[T]) extends Encoder[Id[T]] with Decoder[Id[T]] {
  override def apply(o: Id[T]): Json = {
    Option(o).map(id => Json.fromString(id.toString)).getOrElse(Json.Null)
  }

  override def apply(c: HCursor): Result[Id[T]] = {
    c.as[String].map { id =>
      val clazz = ct.runtimeClass.asInstanceOf[Class[T]]
      Id.create(id, clazz)
    }
  }
}
