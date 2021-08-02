package beam.utils

import javax.management.{
  InstanceNotFoundException,
  MBeanException,
  MBeanServer,
  MalformedObjectNameException,
  ObjectName,
  ReflectionException
}

import com.sun.management.HotSpotDiagnosticMXBean
import java.lang.management.ManagementFactory

object VMUtils {
  private val DIAGNOSTIC_COMMAND_MBEAN_OBJECT_NAME = "com.sun.management:type=DiagnosticCommand"

  def apply(): VMUtils = {
    try {
      val objectName = new ObjectName(DIAGNOSTIC_COMMAND_MBEAN_OBJECT_NAME)
      new VMUtils(objectName)
    } catch {
      case monException: MalformedObjectNameException =>
        throw new RuntimeException(
          "Unable to create an ObjectName and so unable to create instance of VirtualMachineDiagnostics",
          monException
        )
    }
  }

  def parseClassHistogram(jfrClassHistogram: String, takeLines: Int): Seq[VMClassInfo] = {
    val lines = jfrClassHistogram
      .split("\n")
      .filter(_.length > 5)
      .slice(2, takeLines + 2)

    val infos = lines.flatMap { line =>
      val parts = line.split(" ").map(_.trim).filter(_ != "")
      if (parts.length == 4) {
        Some(new VMClassInfo(parts(3), parts(2).toLong, parts(1).toLong))
      } else {
        None
      }
    }

    infos
  }
}

class VMClassInfo(val className: String, val numberOfBytes: Long, val countOfInstances: Long)

sealed trait JFRCommandWithoutArguments {
  def asStr: String
}

object JFRCommandWithoutArguments {

  case object GcClassHistogram extends JFRCommandWithoutArguments {
    def asStr: String = "gcClassHistogram"
  }

  case object GcClassStats extends JFRCommandWithoutArguments {
    def asStr: String = "gcClassStats"
  }

  case object GcRotateLog extends JFRCommandWithoutArguments {
    def asStr: String = "gcRotateLog"
  }

  case object GcRun extends JFRCommandWithoutArguments {
    def asStr: String = "gcRun"
  }

  case object GcRunFinalization extends JFRCommandWithoutArguments {
    def asStr: String = "gcRunFinalization"
  }

  case object Help extends JFRCommandWithoutArguments {
    def asStr: String = "help"
  }

  case object FrCheck extends JFRCommandWithoutArguments {
    def asStr: String = "frCheck"
  }

  case object JfrDump extends JFRCommandWithoutArguments {
    def asStr: String = "jfrDump"
  }

  case object JfrStart extends JFRCommandWithoutArguments {
    def asStr: String = "jfrStart"
  }

  case object JfrStop extends JFRCommandWithoutArguments {
    def asStr: String = "jfrStop"
  }

  case object ThreadPrint extends JFRCommandWithoutArguments {
    def asStr: String = "threadPrint"
  }

  case object VmCheckCommercialFeatures extends JFRCommandWithoutArguments {
    def asStr: String = "vmCheckCommercialFeatures"
  }

  case object VmCommandLine extends JFRCommandWithoutArguments {
    def asStr: String = "vmCommandLine"
  }

  case object VmFlags extends JFRCommandWithoutArguments {
    def asStr: String = "vmFlags"
  }

  case object VmNativeMemory extends JFRCommandWithoutArguments {
    def asStr: String = "vmNativeMemory"
  }

  case object VmSystemProperties extends JFRCommandWithoutArguments {
    def asStr: String = "vmSystemProperties"
  }

  case object VmUnlockCommercialFeatures extends JFRCommandWithoutArguments {
    def asStr: String = "vmUnlockCommercialFeatures"
  }

  case object VmUptime extends JFRCommandWithoutArguments {
    def asStr: String = "vmUptime"
  }

  case object VmVersion extends JFRCommandWithoutArguments {
    def asStr: String = "vmVersion"
  }
}

class VMUtils(val mbeanObjectName: ObjectName) {
  import VMUtils._

  private val server: MBeanServer = ManagementFactory.getPlatformMBeanServer

  def gcRun(): Unit = invoke(JFRCommandWithoutArguments.GcRun)

  def dumpHeap(filePath: String, live: Boolean): Unit = {
    val mxBean = ManagementFactory.newPlatformMXBeanProxy(
      server,
      "com.sun.management:type=HotSpotDiagnostic",
      classOf[HotSpotDiagnosticMXBean]
    )
    mxBean.dumpHeap(filePath, live)
  }

  def gcClassHistogram(takeTopClasses: Int): Seq[VMClassInfo] = {
    gcRun()
    parseClassHistogram(invoke(JFRCommandWithoutArguments.GcClassHistogram), takeTopClasses)
  }

  def invoke(jfrCommand: JFRCommandWithoutArguments): String = {
    try {
      server
        .invoke(mbeanObjectName, jfrCommand.asStr, Array[AnyRef](null), Array[String](classOf[Array[String]].getName))
        .asInstanceOf[String]
    } catch {
      case exception @ (_: InstanceNotFoundException | _: ReflectionException | _: MBeanException) =>
        "ERROR: Unable to access '" + jfrCommand.asStr + "' - " + exception
    }
  }
}
