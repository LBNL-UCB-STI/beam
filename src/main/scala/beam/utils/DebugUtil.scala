package beam.utils

case class CallerInfo(clazz: Class[_], file: sourcecode.File, enclosing: sourcecode.Enclosing, line: sourcecode.Line)

trait DebugUtil {
  // Some magic with scala macros :)
  implicit def getCallerInfo(
    implicit file: sourcecode.File,
    enclosing: sourcecode.Enclosing,
    line: sourcecode.Line
  ): CallerInfo = CallerInfo(this.getClass, file, enclosing, line)
}
