package beam.utils

case class CallerInfo(clazz: Class[_], file: sourcecode.File, enclosing: sourcecode.Enclosing, line: sourcecode.Line) {
  override def toString: String = {
    val sourceFileName = new java.io.File(file.value).getName
    s"$clazz ${enclosing.value}($sourceFileName:${line.value}) ${file.value}:${line.value}"
  }
}

trait DebugUtil {
  // Some magic with scala macros :)
  implicit def getCallerInfo(
    implicit file: sourcecode.File,
    enclosing: sourcecode.Enclosing,
    line: sourcecode.Line
  ): CallerInfo = CallerInfo(this.getClass, file, enclosing, line)
}
