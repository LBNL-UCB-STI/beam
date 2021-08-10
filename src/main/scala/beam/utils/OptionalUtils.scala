package beam.utils

object OptionalUtils {
  import java.util.Optional

  /**
    * Conversions between Scala Option and Java 8 Optional.
    */
  object JavaOptionals {
    import scala.language.implicitConversions

    implicit def toRichOption[T](opt: Option[T]): RichOption[T] = new RichOption[T](opt)

    implicit def toRichOptional[T](optional: Optional[T]): RichOptional[T] =
      new RichOptional[T](optional)
  }

  class RichOption[T](opt: Option[T]) {

    /**
      * Transform this Option to an equivalent Java Optional
      */
    def toOptional: Optional[T] = Optional.ofNullable(opt.getOrElse(null).asInstanceOf[T])
  }

  class RichOptional[T](opt: Optional[T]) {

    /**
      * Transform this Optional to an equivalent Scala Option
      */
    def toOption: Option[T] = if (opt.isPresent) Some(opt.get()) else None
  }
}
