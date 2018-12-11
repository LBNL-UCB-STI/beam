package beam.docs

object ReflectionUtil {

  import scala.reflect.runtime.universe._
  import scala.reflect.ClassTag

  def classAccessors[T: TypeTag]: List[MethodSymbol] =
    typeOf[T].members.collect {
      case m: MethodSymbol if m.isCaseAccessor => m
    }.toList

  def getValue[T: TypeTag: ClassTag](obj: T, memberName: String): Any = {
    val symbol = typeOf[T].member(TermName(memberName)).asMethod

    val m = runtimeMirror(obj.getClass.getClassLoader)
    val im = m.reflect(obj)

    im.reflectMethod(symbol).apply()
  }
}
