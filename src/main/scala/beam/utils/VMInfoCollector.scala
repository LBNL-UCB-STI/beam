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

object VMInfoCollector {
  private val DIAGNOSTIC_COMMAND_MBEAN_OBJECT_NAME = "com.sun.management:type=DiagnosticCommand"

  def apply(): VMInfoCollector = {
    try {
      val objectName = new ObjectName(DIAGNOSTIC_COMMAND_MBEAN_OBJECT_NAME)
      new VMInfoCollector(objectName)
    } catch {
      case monException: MalformedObjectNameException =>
        throw new RuntimeException(
          "Unable to create an ObjectName and so unable to create instance of VirtualMachineDiagnostics",
          monException
        )
    }
  }
}

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

class VMInfoCollector(val mbeanObjectName: ObjectName) {
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

  def gcClassHistogram: String = {
    gcRun()
    invoke(JFRCommandWithoutArguments.GcClassHistogram)
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
